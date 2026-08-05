/*
 * Copyright 2026 The Proj4J Contributors.
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
package org.locationtech.proj4j.pipeline;

import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.Registry;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.PrimeMeridian;
import org.locationtech.proj4j.gie.GieIoUnits;
import org.locationtech.proj4j.parser.Proj4Parser;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.vertical.VGridShiftOperator;

/**
 * A classic PROJ.4-style operation — a projection plus the "cs2cs emulation"
 * helper steps PROJ hides inside it.
 *
 * <h2>Why this class is the crux</h2>
 *
 * <p>A GIGS pipeline step is almost always {@code +init=epsg:NNNN}, whose expansion
 * is a legacy proj-string carrying {@code +towgs84}, {@code +pm}, {@code +axis} and
 * {@code +units}. None of those are handled by the projection formula. PROJ handles
 * them in {@code cs2cs_emulation_setup} ({@code 9.8.1:src/create.cpp:48-201}), which
 * builds up to five <em>hidden</em> sub-operations, and in
 * {@code fwd_prepare}/{@code fwd_finalize} ({@code src/fwd.cpp:39-165}) and
 * {@code inv_prepare}/{@code inv_finalize} ({@code src/inv.cpp:39-140}), which
 * invoke them in a fixed order. That order <em>is</em> the semantics of a legacy
 * {@code +init=} string, and it is what makes a two-step GIGS pipeline mean
 * "local CRS to WGS84 hub to local CRS".
 *
 * <p>The direction convention follows from the order, and is worth stating because
 * it looks inverted: the operation's <b>left</b> side is the WGS84 hub and its
 * <b>right</b> side is the local system. So {@code +init=X +inv} converts
 * <em>from</em> X to the hub, and {@code +init=Y} converts from the hub to Y. That
 * is exactly the shape every GIGS file uses, and exactly what {@code cs2cs} does
 * with a source and a target CRS.
 *
 * <h2>The forward order, verbatim</h2>
 *
 * <ol>
 * <li><b>{@code fwd_prepare}</b>, only when the left side is
 *     {@link GieIoUnits#RADIANS}:
 *     <ol type="a">
 *     <li>reject {@code |phi| - pi/2 > 1e-12} and {@code |lam| > 10} <em>radians</em>
 *         — note radians, so the longitude bound is about +/-573 degrees, not 180;</li>
 *     <li>clamp {@code phi} into {@code [-pi/2, pi/2]};</li>
 *     <li>{@code adjlon} unless {@code +over};</li>
 *     <li>the datum shift: geodetic-WGS84 to cartesian, Helmert into the local
 *         frame, cartesian back to geodetic on the <em>local</em> ellipsoid;</li>
 *     <li>{@code lam = (lam - from_greenwich) - lam0}, then {@code adjlon} again.</li>
 *     </ol></li>
 * <li>the projection formula;</li>
 * <li><b>{@code fwd_finalize}</b>: scale by {@code a}, add the false easting and
 *     northing, scale to the target unit — then the {@code +axis} swap, last.</li>
 * </ol>
 *
 * <p>The inverse is the exact mirror, including that {@code +axis} is undone
 * <em>first</em>.
 *
 * <h2>What is delegated to proj4j and what is not</h2>
 *
 * <p>{@code Projection.projectRadians} already bundles steps 1(e)-{@code lam0} and
 * all of step 2 and 3-except-{@code +axis}: it subtracts {@code lon_0}, normalises,
 * runs the formula, then applies {@code a}, the false easting/northing and
 * {@code +units}. So this class subtracts only {@code from_greenwich} and lets
 * proj4j do the rest, which keeps one implementation of the affine post-multiply
 * rather than two. The one gap is that proj4j skips its longitude normalisation
 * when {@code lon_0 == 0} while PROJ always normalises, so that case is handled
 * here.
 *
 * <p><b>{@code +proj=longlat} and {@code +proj=geocent} bypass proj4j entirely.</b>
 * {@code LongLatProjection} installs {@code Units.DEGREES}, so
 * {@code projectRadians} multiplies its output by {@code 180/pi} — PROJ's
 * {@code latlong} is {@code RADIANS} on both sides and its formula is the identity,
 * so going through proj4j would mean converting to degrees and back.
 * {@code GeocentProjection} is worse: both of its methods read the <em>destination</em>
 * coordinate instead of the source ({@code GeocentProjection.java:9-20}), so it
 * cannot produce a correct answer at all. Both are handled here with
 * {@link CartConversion}, which is upstream's own {@code +proj=cart}.
 *
 * <h2>{@code +geoidgrids} and {@code +nadgrids} are built; {@code +geoc} is not</h2>
 *
 * <p>{@code +geoidgrids} becomes a hidden {@link VGridShiftOperator} —
 * {@code create.cpp:88-105} builds
 * {@code break_cs2cs_recursion proj=vgridshift grids=<list>} and stores it as
 * {@code P->vgridshift}, and {@code fwd_prepare} runs it <em>forward</em> immediately
 * after the datum shift and before the {@code lam0} subtraction
 * ({@code fwd.cpp:96-99}), which is where it sits below. The inverse runs it before
 * the datum shift on the way back ({@code inv.cpp:117-119}). Its sign convention is
 * {@code z += value * multiplier} with {@code multiplier} defaulting to {@code -1},
 * i.e. an ellipsoidal height becomes an orthometric one.
 *
 * <p>{@code +nadgrids} becomes a hidden {@link HGridShiftOperator}
 * ({@code create.cpp:107-124}), and it is <b>not</b> just another branch of the datum
 * shift — it <em>replaces</em> the whole Helmert branch. Three details, each of which
 * reads backwards until you check the source:
 *
 * <ol>
 * <li><b>{@code +nadgrids} suppresses the {@code towgs84} Helmert, and the cartesian
 *     round-trip with it.</b> {@code create.cpp:125} is
 *     {@code p = P->hgridshift ? nullptr : pj_param_exists(P->params, "towgs84")}, so
 *     the all-zero test that would have set {@code do_cart} is never reached either.
 *     A grid shift is a geographic-to-geographic operation and needs no change of
 *     ellipsoid: the projection formula that follows already uses the local one.</li>
 * <li><b>An explicit {@code +towgs84} does not beat a {@code +datum=} whose definition
 *     is a grid.</b> {@code pj_datum_set} tests {@code nadgrids} first and reaches
 *     {@code towgs84} only in the {@code else}, and the datum's {@code defn} has by
 *     then been <em>appended</em> to the paralist — so {@code +datum=potsdam
 *     +towgs84=1,2,3} is a grid shift, not a Helmert.</li>
 * <li><b>The forward direction runs the grid <em>inverse</em></b>
 *     ({@code fwd.cpp:86}, {@code proj_trans(P->hgridshift, PJ_INV, coo)}), because the
 *     operation's left-hand side is the WGS84 hub and a datum grid is defined from the
 *     local system towards it. {@code inv_finalize} runs it {@code PJ_FWD}
 *     ({@code inv.cpp:124}).</li>
 * </ol>
 *
 * <h2>{@code +geoc}, and the guard that makes it a no-op more often than not</h2>
 *
 * <p>{@code P->geoc = (P->es != 0.0 && pj_param(ctx, start, "bgeoc").i)}
 * ({@code init.cpp:598}) — so on a sphere the flag is read, accepted, and has no
 * effect, because the geocentric and geographic latitudes coincide there.
 * {@code pj_geocentric_latitude} ({@code conversions/geoc.cpp:37-64}) then declines a
 * second time, within a nanoradian of either pole, "so very close (the last
 * centimeter) to the poles no conversion takes place": {@code tan} goes through the
 * roof there while the two latitudes converge, so copying the input is both safer and
 * more accurate than computing.
 *
 * <p>Note the direction, which is the opposite of the name's suggestion:
 * {@code fwd_prepare} calls it {@code PJ_INV} ({@code fwd.cpp:81}), because a
 * {@code +geoc} operation's input <em>is</em> geocentric and has to be made geographic
 * before anything else touches it. {@code inv_finalize} calls it {@code PJ_FWD}
 * ({@code inv.cpp:140}), last of all.
 *
 * <h2>The vertical unit</h2>
 *
 * <p>{@code +vunits}/{@code +vto_meter}/{@code +z_0} are read here rather than in
 * {@code Proj4Parser}, because {@code proj.Projection} has no vertical unit at all —
 * it never touches {@code z}. The rules are {@code init.cpp:715-750}'s, and they
 * mirror the horizontal pair exactly: {@code +vunits} is looked up in
 * {@code pj_list_linear_units()} and, when present, <b>{@code +vto_meter} is never
 * read</b>; when neither is given the vertical unit <em>falls back to the horizontal
 * one</em>, so {@code +units=ft} scales {@code z} as well. The scaling itself is
 * {@code fwd_finalize}'s {@code z = vfr_meter * (z + z0)} and
 * {@code inv_prepare}'s {@code z = vto_meter * z - z0}.
 *
 * <p>Not thread-safe: proj4j {@code Projection} instances are mutable, and
 * {@code +proj=cass} writes seventeen instance fields on the hot path.
 */
final class Cs2csOperator implements PipelineOperator {

    /** {@code PJ_EPS_LAT}, in radians. */
    static final double EPS_LAT = 1e-12;

    /** {@code M_HALFPI}. */
    static final double HALF_PI = Math.PI / 2.0;

    /** {@code fwd_prepare}'s longitude bound, in radians ({@code fwd.cpp:65}). */
    static final double MAX_LAM = 10.0;

    /** {@code SPI} from {@code proj_internal.h} — deliberately a hair above {@code M_PI}. */
    private static final double SPI = 3.14159265359;

    private static final double TWO_PI = Math.PI * 2.0;

    /** WGS84's semi-major axis, as {@code create.cpp:131} spells it. */
    private static final double WGS84_A = 6378137.0;

    /** WGS84's first eccentricity squared, as {@code create.cpp:132} spells it. */
    private static final double WGS84_ES = 0.0066943799901413;

    /** Which formula sits between prepare and finalize. */
    private enum Kernel {
        /** {@code +proj=longlat} and its three aliases: the identity, radians both sides. */
        LONGLAT,
        /** {@code +proj=geocent}: radians in, cartesian metres out. */
        GEOCENT,
        /** Anything else in {@code Registry}: radians in, scaled metres out. */
        PROJECTION
    }

    private final String description;
    private final Kernel kernel;
    private final Projection projection;
    private final double lam0;
    private final double fromGreenwich;
    private final boolean over;
    private final double frMeter;
    private final double toMeter;

    /** {@code P->vto_meter}; falls back to {@link #toMeter} when no vertical unit is given. */
    private final double vToMeter;
    /** {@code P->vfr_meter}, i.e. {@code 1 / vToMeter}. */
    private final double vFrMeter;
    /** {@code P->z0}, always metres ({@code init.cpp:662}). */
    private final double z0;
    /** Whether the vertical affine is the identity, so it can be skipped entirely. */
    private final boolean verticalAffineIsIdentity;

    private final CartConversion cart;
    private final CartConversion cartWgs84;
    private final HelmertConversion helmert;
    private final AxisSwapOperator axisSwap;
    /** {@code P->vgridshift}, built from {@code +geoidgrids}; null when absent. */
    private final VGridShiftOperator vgridshift;
    /** {@code P->hgridshift}, built from {@code +nadgrids}; null when absent. */
    private final HGridShiftOperator hgridshift;

    /** {@code P->geoc} ({@code init.cpp:598}): {@code +geoc} <b>and</b> {@code es != 0}. */
    private final boolean geoc;
    /** {@code P->one_es}, i.e. {@code 1 - es}; only read when {@link #geoc}. */
    private final double oneEs;
    /** {@code P->rone_es}, i.e. {@code 1 / (1 - es)}; only read when {@link #geoc}. */
    private final double rOneEs;

    private GieIoUnits left;
    private GieIoUnits right;

    /**
     * @param registry the projection registry to resolve {@code +proj=} against
     * @param params   the step's fully expanded parameter list — {@code +init=} and
     *                 {@code +datum=} already appended, so first-match-wins gives
     *                 PROJ's precedence
     */
    Cs2csOperator(final Registry registry, final ProjParams params) {
        final String projName = params.value("proj");
        this.description = projName == null ? params.toString() : projName;

        final CoordinateReferenceSystem crs = new Proj4Parser(registry).parse(null, params.toProj4Args());
        if (crs == null || crs.getProjection() == null) {
            throw new PipelineDefinitionException(PipelineErrorCode.NOT_IMPLEMENTED_HERE,
                    "proj4j produced no projection for " + params);
        }
        this.projection = crs.getProjection();

        if (isLongLat(projName)) {
            this.kernel = Kernel.LONGLAT;
            this.left = GieIoUnits.RADIANS;
            this.right = GieIoUnits.RADIANS;
        } else if ("geocent".equals(projName)) {
            this.kernel = Kernel.GEOCENT;
            this.left = GieIoUnits.RADIANS;
            this.right = GieIoUnits.CARTESIAN;
        } else {
            this.kernel = Kernel.PROJECTION;
            this.left = GieIoUnits.RADIANS;
            // Every PROJ_HEAD projection declares CLASSIC, which pj_right folds to
            // PROJECTED (proj_internal.h:882-883).
            this.right = GieIoUnits.CLASSIC;
        }

        this.lam0 = projection.getProjectionLongitude();
        this.fromGreenwich = offsetFromGreenwich(projection.getPrimeMeridian());
        this.over = params.booleanValue("over");
        this.frMeter = projection.getFromMetres();
        this.toMeter = 1.0 / frMeter;

        // init.cpp:715-750. +vunits wins over +vto_meter; absent both, the vertical unit is
        // the horizontal one.
        this.vToMeter = verticalToMeter(params, toMeter);
        this.vFrMeter = 1.0 / vToMeter;
        this.z0 = params.doubleValue("z_0", 0.0);
        this.verticalAffineIsIdentity = vToMeter == 1.0 && z0 == 0.0;

        final Ellipsoid ellipsoid = projection.getEllipsoid();
        final double aOrig = ellipsoid.getEquatorRadius();
        final double esOrig = ellipsoid.getEccentricitySquared();

        // init.cpp:598. The `es != 0` half is not an optimisation: on a sphere the two
        // latitudes are equal, so +geoc is legitimately accepted and ignored there.
        this.geoc = esOrig != 0.0 && params.booleanValue("geoc");
        this.oneEs = 1.0 - esOrig;
        this.rOneEs = 1.0 / (1.0 - esOrig);

        // create.cpp:107-124. The +nadgrids helper, built BEFORE the towgs84 one because
        // its existence is what decides whether the towgs84 one is looked up at all.
        // Upstream's guard is strlen(p->param) > strlen("nadgrids="), so a bare
        // +nadgrids with no value, or an empty one, builds nothing rather than failing.
        final String nadgrids = nadgridsSpec(params);
        this.hgridshift = nadgrids == null || nadgrids.isEmpty()
                ? null
                : HGridShiftOperator.fromGrids(nadgrids);

        // create.cpp:125: `p = P->hgridshift ? nullptr : pj_param_exists(params, "towgs84")`.
        // A grid shift suppresses the Helmert *and* the do_cart branch below it, because
        // the all-zero test that sets do_cart lives inside the towgs84 loop.
        final double[] datumParams = hgridshift != null ? null : datumParams(registry, params);
        final boolean isGeocent = kernel == Kernel.GEOCENT;

        // create.cpp:124-158. An all-zero towgs84 is ignored, except that a
        // non-WGS84 ellipsoid still forces the cartesian round-trip, because the
        // change of ellipsoid is itself a change of coordinates.
        boolean doCart = false;
        HelmertConversion h = null;
        if (datumParams != null) {
            if (isAllZero(datumParams)) {
                if (!isWgs84Ellipsoid(aOrig, esOrig)) {
                    doCart = true;
                }
            } else {
                h = new HelmertConversion(datumParams);
            }
        }
        this.helmert = h;

        if (isGeocent || helmert != null || doCart) {
            this.cart = new CartConversion(aOrig, esOrig);
            // create.cpp:192 - no WGS84 leg for a geocentric operation, because
            // its own right-hand side already is cartesian.
            this.cartWgs84 = isGeocent ? null : new CartConversion(WGS84_A, WGS84_ES);
        } else {
            this.cart = null;
            this.cartWgs84 = null;
        }

        // create.cpp:88-105. The +geoidgrids helper. Note upstream's own guard:
        // strlen(p->param) > strlen("geoidgrids="), so a bare +geoidgrids with no value or
        // an empty one builds nothing at all rather than failing.
        //
        // The def upstream builds is a *fresh, independent* parameter string, so the outer
        // operation's own +multiplier is deliberately NOT inherited: the auto-inserted step
        // always runs at the vgridshift default of -1. Reading +multiplier here would let a
        // token PROJ ignores flip the sign of every height.
        final String geoidGrids = params.value("geoidgrids");
        this.vgridshift = geoidGrids == null || geoidGrids.isEmpty()
                ? null
                : VGridShiftOperator.fromGrids(geoidGrids, VGridShiftOperator.DEFAULT_MULTIPLIER);

        // create.cpp:70-88. The +axis helper is built whenever +axis is present:
        // upstream's guard compares the whole token "axis=enu" against "enu" and so
        // never matches, making even +axis=enu insert an (identity) swap.
        this.axisSwap = params.has("axis")
                ? new AxisSwapOperator(ProjParams.parse("axis=" + params.value("axis")))
                : null;
    }

    private static boolean isLongLat(final String projName) {
        return "longlat".equals(projName) || "latlong".equals(projName)
                || "lonlat".equals(projName) || "latlon".equals(projName);
    }

    /**
     * The {@code nadgrids=} definitions of PROJ's built-in datum table, verbatim from
     * {@code 9.8.1:src/datums.cpp:44-50} — the only two of its ten entries whose
     * {@code defn} is a grid list rather than a {@code towgs84=}:
     * <pre>
     * {"NAD27",   "nadgrids=@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat", "clrk66", ...},
     * {"potsdam", "nadgrids=@BETA2007.gsb",                            "bessel", ...},
     * </pre>
     *
     * <p>Held here rather than read off the {@link Datum} object, for two reasons, and
     * the second one was measured the hard way.
     *
     * <p><b>First</b>, {@code Datum} keeps the <em>resolved</em> {@code List<Grid>} and
     * not the list as written, and {@code create.cpp} needs the string:
     * {@code @}-optionality is part of the token, and it is what makes a missing grid a
     * pass-through rather than an error.
     *
     * <p><b>Second, {@code Datum.getTransformType()} is not a safe question to ask.</b>
     * {@code Datum.POTSDAM} and {@code Datum.NAD27} resolve their grids in a
     * <em>static initialiser</em>, so whether they report {@code TYPE_GRIDSHIFT} depends
     * on whether the resolver chain was configured before the class happened to load.
     * Observed: {@code DHDN_ETRS89.gie} scored 64/64 when the harness loaded the grid
     * resolver first and 32/32 when an unrelated earlier test touched {@code Registry}
     * first — the same code, the same grid on the same classpath, a different answer.
     * Reading the datum <em>table</em> instead makes the decision a function of the
     * definition alone, and defers the question of whether the file exists to
     * {@link HorizontalGrids#open}, which is called at operator-construction time and is
     * therefore ordered after any resolver setup.
     *
     * <p>This also matches {@code pj_init} more closely than the alternative did.
     * {@code pj_datum_set} appends {@code nadgrids=@BETA2007.gsb} unconditionally and
     * {@code cs2cs_emulation_setup} builds an {@code hgridshift} from it whatever the
     * file system says; the Helmert that {@code Datum.POTSDAM} keeps as a fallback is
     * {@code cs2cs}'s CRS-layer behaviour, which is a different code path with a
     * different answer (see that constant's javadoc, which measures both).
     */
    private static final String[][] LEGACY_DATUM_NADGRIDS = {
        {"NAD27", "@conus,@alaska,@ntv2_0.gsb,@ntv1_can.dat"},
        {"potsdam", "@BETA2007.gsb"},
    };

    /**
     * The effective {@code +nadgrids=} list: the token if the definition carries one,
     * otherwise whatever a {@code +datum=} naming a grid-shift datum expands to.
     *
     * <p>{@code pj_datum_set} <em>appends</em> the datum's {@code defn} to the paralist
     * and then tests {@code nadgrids} <b>before</b> {@code towgs84}, in an
     * {@code if}/{@code else if}. So a grid definition reached through {@code +datum=}
     * outranks an explicit {@code +towgs84} written by the user, which is the one
     * precedence in this file that is not first-match-wins.
     *
     * @param params the step's fully expanded parameter list
     * @return the list as written, or {@code null} when the step declares no grid shift
     */
    private static String nadgridsSpec(final ProjParams params) {
        final String explicit = params.value("nadgrids");
        if (explicit != null) {
            return explicit;
        }
        final String datumCode = params.value("datum");
        if (datumCode == null) {
            return null;
        }
        for (int i = 0; i < LEGACY_DATUM_NADGRIDS.length; i++) {
            if (LEGACY_DATUM_NADGRIDS[i][0].equalsIgnoreCase(datumCode)) {
                return LEGACY_DATUM_NADGRIDS[i][1];
            }
        }
        // Not one of the two grid-shift datums. Whether the code exists at all is
        // datumParams's question, and it is the one that reports an unknown datum.
        return null;
    }

    /**
     * {@code P->vto_meter} ({@code init.cpp:715-750}).
     *
     * <p>Three branches, in this exact order, and the first two are mutually exclusive:
     * {@code +vunits} is looked up in {@code pj_list_linear_units()} and its own
     * {@code to_meter} string is what gets parsed, so <b>{@code +vto_meter} is never read
     * when {@code +vunits} is present</b>; otherwise {@code +vto_meter} is parsed with the
     * {@code num/den} ratio syntax; otherwise the value <em>is</em> the horizontal
     * {@code to_meter}.
     *
     * <p>Only <b>linear</b> units are accepted, because upstream searches
     * {@code pj_list_linear_units()} and nothing else — so {@code +vunits=deg} is
     * "Invalid value for vunits", not a radian conversion.
     *
     * @param params            the step's parameters
     * @param horizontalToMeter {@code P->to_meter}, the fallback
     * @return the vertical {@code to_meter}, guaranteed positive and finite
     */
    private static double verticalToMeter(final ProjParams params, final double horizontalToMeter) {
        final String vunits = params.value("vunits");
        if (vunits != null) {
            final PipelineUnits.Resolution unit = PipelineUnits.resolve(vunits);
            if (!unit.isKnown() || unit.linear() != 1) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "+vunits=" + vunits + " is not one of PROJ's linear units");
            }
            return unit.factor();
        }
        final String vtoMeter = params.value("vto_meter");
        if (vtoMeter != null) {
            return parseToMeter(vtoMeter);
        }
        return horizontalToMeter;
    }

    /**
     * {@code +vto_meter}'s value syntax: a plain double, or a {@code num/den} ratio, which is
     * how PROJ's own unit table spells {@code us-ft} ({@code "1200/3937"}).
     *
     * @param raw the value as written
     * @return the factor, validated positive and finite
     */
    private static double parseToMeter(final String raw) {
        final String v = raw.trim();
        final int slash = v.indexOf('/');
        double value;
        try {
            if (slash >= 0) {
                final double denominator = Double.parseDouble(v.substring(slash + 1).trim());
                if (denominator == 0.0) {
                    throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                            "+vto_meter=" + raw + " has a zero denominator");
                }
                value = Double.parseDouble(v.substring(0, slash).trim()) / denominator;
            } else {
                value = Double.parseDouble(v);
            }
        } catch (final NumberFormatException e) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "invalid +vto_meter value: " + raw, e);
        }
        if (!(value > 0.0) || Double.isInfinite(value)) {
            throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                    "+vto_meter=" + raw + " must be > 0");
        }
        return value;
    }

    /**
     * {@code pj_datum_set}'s {@code P->datum_params}: {@code +towgs84} if present,
     * otherwise whatever {@code +datum=} expands to.
     *
     * <p>Translations in metres, rotations in <b>radians</b>, element 6 holding
     * {@code 1 + s/1e6}. proj4j's {@code datum.Datum} already stores exactly that,
     * so {@code +datum=} needs no conversion and {@code +towgs84=} needs the same
     * one {@code Datum}'s constructor applies.
     *
     * @return {@code null} when the step declares no datum shift at all
     */
    private static double[] datumParams(final Registry registry, final ProjParams params) {
        final String towgs84 = params.value("towgs84");
        if (towgs84 != null) {
            return parseTowgs84(towgs84);
        }
        final String datumCode = params.value("datum");
        if (datumCode != null) {
            final Datum datum = registry.getDatum(datumCode);
            if (datum == null) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "unknown datum: " + datumCode);
            }
            // A grid-shift datum never reaches here: nadgridsSpec resolved it to an
            // hgridshift and the caller then skipped this method entirely, exactly as
            // create.cpp:125 skips the towgs84 lookup. Reading only the Helmert
            // parameters would have silently dropped the grid.
            final double[] transform = datum.getTransformToWGS84();
            return transform == null ? null : transform.clone();
        }
        return null;
    }

    /**
     * {@code datum_set.cpp:113-140}. Seven slots, zero filled; the arcsecond and
     * ppm conversions happen only when at least one of slots 3..6 is non-zero, which
     * is what makes an all-zero seven-value {@code +towgs84} indistinguishable from
     * a three-value one — and that in turn is what {@code create.cpp}'s all-zero
     * test relies on.
     */
    private static double[] parseTowgs84(final String value) {
        final double[] out = new double[7];
        final String[] fields = value.split(",");
        final int n = Math.min(fields.length, 7);
        for (int i = 0; i < n; i++) {
            final String field = fields[i].trim();
            if (field.isEmpty()) {
                continue;
            }
            try {
                out[i] = Double.parseDouble(field);
            } catch (final NumberFormatException e) {
                throw new PipelineDefinitionException(PipelineErrorCode.ILLEGAL_ARG_VALUE,
                        "invalid +towgs84 value: " + value, e);
            }
        }
        if (out[3] != 0.0 || out[4] != 0.0 || out[5] != 0.0 || out[6] != 0.0) {
            out[3] *= org.locationtech.proj4j.util.ProjectionMath.SECONDS_TO_RAD;
            out[4] *= org.locationtech.proj4j.util.ProjectionMath.SECONDS_TO_RAD;
            out[5] *= org.locationtech.proj4j.util.ProjectionMath.SECONDS_TO_RAD;
            out[6] = out[6] / org.locationtech.proj4j.util.ProjectionMath.MILLION + 1.0;
        }
        return out;
    }

    private static boolean isAllZero(final double[] p) {
        for (int i = 0; i < p.length; i++) {
            if (p[i] != 0.0) {
                return false;
            }
        }
        return true;
    }

    /** {@code create.cpp:133-134}, tolerances included. */
    private static boolean isWgs84Ellipsoid(final double a, final double es) {
        return Math.abs(a - WGS84_A) < 1e-8 && Math.abs(es - WGS84_ES) < 1e-15;
    }

    /**
     * {@code P->from_greenwich}, in radians east.
     *
     * <p>Read by probing {@link PrimeMeridian#toGreenwich}, which adds the offset,
     * rather than by re-tabulating PROJ's fourteen named meridians: the table
     * proj4j already has is the one {@code Proj4Parser} resolved {@code +pm}
     * against, and a second copy of it could drift.
     */
    private static double offsetFromGreenwich(final PrimeMeridian pm) {
        if (pm == null) {
            return 0.0;
        }
        final ProjCoordinate probe = new ProjCoordinate(0.0, 0.0, 0.0);
        pm.toGreenwich(probe);
        return probe.x;
    }

    /** {@code adjlon} ({@code proj_internal.h:414-423}). */
    private static double adjlon(double lon) {
        if (Math.abs(lon) <= SPI) {
            return lon;
        }
        lon += Math.PI;
        lon -= TWO_PI * Math.floor(lon / TWO_PI);
        lon -= Math.PI;
        return lon;
    }

    // ------------------------------------------------------------------ execute

    @Override
    public void forward(final double[] coord) {
        if (left == GieIoUnits.RADIANS) {
            prepareAngular(coord);
        } else if (left == GieIoUnits.CARTESIAN && helmert != null) {
            // fwd.cpp:114-116: gridshifts are unsupported on cartesian input, but a
            // Helmert is applied in reverse.
            helmert.inverse(coord);
        }

        switch (kernel) {
            case LONGLAT:
                verticalFinalize(coord);
                break;
            case GEOCENT:
                cart.forward(coord);
                coord[0] *= frMeter;
                coord[1] *= frMeter;
                coord[2] *= frMeter;
                break;
            default:
                projectForward(coord);
                verticalFinalize(coord);
                break;
        }

        if (axisSwap != null) {
            axisSwap.forward(coord);
        }
    }

    /**
     * {@code fwd_finalize}'s vertical line: {@code z = vfr_meter * (z + z0)}.
     *
     * <p>Reached for {@code PJ_IO_UNITS_CLASSIC}/{@code PROJECTED} ({@code fwd.cpp:145}) and
     * for {@code PJ_IO_UNITS_RADIANS} ({@code fwd.cpp:157}) — the same expression in both, so
     * one method serves both kernels. {@code CARTESIAN} is not one of them: a geocentric
     * operation scales {@code z} by the <em>horizontal</em> {@code fr_meter} along with
     * {@code x} and {@code y}, which the {@code GEOCENT} branch already does.
     *
     * @param coord the coordinate, mutated in place
     */
    private void verticalFinalize(final double[] coord) {
        if (verticalAffineIsIdentity) {
            return;
        }
        coord[2] = vFrMeter * (coord[2] + z0);
    }

    /** {@code inv_prepare}'s mirror: {@code z = vto_meter * z - z0}. */
    private void verticalPrepare(final double[] coord) {
        if (verticalAffineIsIdentity) {
            return;
        }
        coord[2] = vToMeter * coord[2] - z0;
    }

    /** {@code fwd_prepare}'s {@code PJ_IO_UNITS_RADIANS} branch ({@code fwd.cpp:54-113}). */
    private void prepareAngular(final double[] coord) {
        if (coord[0] == Double.POSITIVE_INFINITY || coord[1] == Double.POSITIVE_INFINITY
                || coord[2] == Double.POSITIVE_INFINITY) {
            throw new ProjectionException("HUGE_VAL ordinate: fwd_prepare returns proj_coord_error()");
        }
        if (Math.abs(coord[1]) - HALF_PI > EPS_LAT) {
            throw new ProjectionException("Invalid latitude: |phi| - pi/2 = "
                    + (Math.abs(coord[1]) - HALF_PI) + " rad exceeds PJ_EPS_LAT");
        }
        if (coord[0] > MAX_LAM || coord[0] < -MAX_LAM) {
            throw new ProjectionException("Invalid longitude: " + coord[0]
                    + " rad is outside +/-10");
        }
        if (coord[1] > HALF_PI) {
            coord[1] = HALF_PI;
        } else if (coord[1] < -HALF_PI) {
            coord[1] = -HALF_PI;
        }
        // fwd.cpp:80-81, "If input latitude is geocentrical, convert to geographical".
        // Note PJ_INV, and note that it happens after the clamp and before adjlon.
        if (geoc) {
            coord[1] = geocentricLatitude(coord[1], false);
        }
        if (!over) {
            coord[0] = adjlon(coord[0]);
        }

        // The datum shift, from the WGS84 hub into the local frame.
        //
        // fwd.cpp:86-95 is an if/else if, not two independent steps: a grid shift and a
        // Helmert are alternatives, and the grid runs PJ_INV here because the hub is on
        // the left and a datum grid is defined local-to-hub.
        if (hgridshift != null) {
            hgridshift.inverse(coord);
        } else if (helmert != null || (cartWgs84 != null && cart != null)) {
            if (cartWgs84 != null) {
                cartWgs84.forward(coord);
                if (helmert != null) {
                    helmert.inverse(coord);
                }
                cart.inverse(coord);
            } else if (helmert != null) {
                // A geocentric operation carrying a real +towgs84: upstream would
                // dereference a null cart_wgs84 here, so there is nothing to be
                // faithful to. Apply the Helmert in the local cartesian frame.
                cart.forward(coord);
                helmert.inverse(coord);
                cart.inverse(coord);
            }
        }

        // fwd.cpp:96-99, "Go orthometric from geometric". After the datum shift, before the
        // lam0 subtraction: a vertical grid is indexed by the *local* frame's geographic
        // position, so it must not see the central-meridian-relative longitude.
        if (vgridshift != null) {
            vgridshift.forward(coord);
        }

        // PROJ: lam = (lam - from_greenwich) - lam0, then adjlon.
        //
        // For Kernel.PROJECTION the "- lam0, then normalise" half belongs to
        // Projection.projectRadians, which does it iff lon_0 != 0. So only the
        // prime-meridian subtraction happens here, plus the normalisation proj4j
        // would otherwise skip when lon_0 == 0.
        coord[0] -= fromGreenwich;
        if (kernel != Kernel.PROJECTION) {
            coord[0] -= lam0;
        }
        if (!over && (kernel != Kernel.PROJECTION || lam0 == 0.0)) {
            coord[0] = adjlon(coord[0]);
        }
    }

    private void projectForward(final double[] coord) {
        final ProjCoordinate src = new ProjCoordinate(coord[0], coord[1], coord[2]);
        final ProjCoordinate dst = new ProjCoordinate(Double.NaN, Double.NaN, Double.NaN);
        projection.projectRadians(src, dst);
        coord[0] = dst.x;
        coord[1] = dst.y;
    }

    @Override
    public void inverse(final double[] coord) {
        if (axisSwap != null) {
            axisSwap.inverse(coord);
        }

        switch (kernel) {
            case LONGLAT:
                verticalPrepare(coord);
                break;
            case GEOCENT:
                coord[0] *= toMeter;
                coord[1] *= toMeter;
                coord[2] *= toMeter;
                cart.inverse(coord);
                break;
            default:
                // inv_prepare de-scales z along with x and y, before the formula runs.
                verticalPrepare(coord);
                projectInverse(coord);
                break;
        }

        if (left == GieIoUnits.RADIANS) {
            finalizeAngular(coord);
        }
    }

    private void projectInverse(final double[] coord) {
        if (!projection.hasInverse()) {
            throw new PipelineDefinitionException(PipelineErrorCode.NO_INVERSE_OP,
                    "+proj=" + description + " has no inverse in proj4j");
        }
        final ProjCoordinate src = new ProjCoordinate(coord[0], coord[1], coord[2]);
        final ProjCoordinate dst = new ProjCoordinate(Double.NaN, Double.NaN, Double.NaN);
        projection.inverseProjectRadians(src, dst);
        coord[0] = dst.x;
        coord[1] = dst.y;
    }

    /** {@code inv_finalize}'s {@code PJ_IO_UNITS_RADIANS} branch ({@code inv.cpp:102-140}). */
    private void finalizeAngular(final double[] coord) {
        coord[0] += fromGreenwich;
        if (kernel != Kernel.PROJECTION) {
            coord[0] += lam0;
        }
        if (!over) {
            coord[0] = adjlon(coord[0]);
        }
        // inv.cpp:117-119, "Go geometric from orthometric": the exact mirror, so the vertical
        // shift is undone *before* the datum shift rather than after it.
        if (vgridshift != null) {
            vgridshift.inverse(coord);
        }
        // inv.cpp:124-133, the exact mirror: PJ_FWD for the grid, and still an
        // if/else if against the Helmert branch.
        if (hgridshift != null) {
            hgridshift.forward(coord);
        } else if (helmert != null || (cartWgs84 != null && cart != null)) {
            if (cartWgs84 != null) {
                cart.forward(coord);
                if (helmert != null) {
                    helmert.forward(coord);
                }
                cartWgs84.inverse(coord);
            } else if (helmert != null) {
                cart.forward(coord);
                helmert.forward(coord);
                cart.inverse(coord);
            }
        }
        // inv.cpp:139-140, last of all: "If input latitude was geocentrical, convert
        // back to geocentrical". PJ_FWD this time.
        if (geoc) {
            coord[1] = geocentricLatitude(coord[1], true);
        }
    }

    /**
     * {@code pj_geocentric_latitude} ({@code conversions/geoc.cpp:37-64}).
     *
     * <p>Two escapes, both upstream's and both load-bearing. Within
     * {@code M_HALFPI - 1e-9} of a pole the input is returned unchanged, because
     * {@code tan} diverges there while the geocentric and geographic latitudes converge
     * — so computing would be both slower and worse. On a sphere ({@code es == 0}) the
     * two are identical everywhere; that case never reaches here, because
     * {@link #geoc} folds it in at construction exactly as {@code init.cpp:598} does.
     *
     * @param phi     latitude in radians
     * @param forward {@code PJ_FWD}, i.e. geographic to geocentric; {@code false} for
     *                {@code PJ_INV}
     * @return the converted latitude in radians
     */
    private double geocentricLatitude(final double phi, final boolean forward) {
        final double limit = HALF_PI - 1e-9;
        if (phi > limit || phi < -limit) {
            return phi;
        }
        return Math.atan((forward ? oneEs : rOneEs) * Math.tan(phi));
    }

    // ------------------------------------------------------------------- units

    @Override
    public GieIoUnits declaredLeft() {
        return left;
    }

    @Override
    public GieIoUnits declaredRight() {
        return right;
    }

    @Override
    public void overrideUnits(final GieIoUnits newLeft, final GieIoUnits newRight) {
        // Only reachable for an operator declaring WHATEVER on both sides, which a
        // classic projection never does.
        this.left = newLeft;
        this.right = newRight;
    }

    @Override
    public boolean hasInverse() {
        return kernel != Kernel.PROJECTION || projection.hasInverse();
    }

    @Override
    public String description() {
        return description;
    }

    /** The proj4j projection behind this step, for tests and diagnostics. */
    Projection projection() {
        return projection;
    }

    /**
     * The hidden {@code +proj=vgridshift} step {@code +geoidgrids} produced.
     *
     * @return the operator, or {@code null} when the step declares no {@code +geoidgrids}
     */
    VGridShiftOperator vgridshift() {
        return vgridshift;
    }

    /**
     * The hidden {@code +proj=hgridshift} step {@code +nadgrids} — or a {@code +datum=}
     * whose definition is a grid list — produced.
     *
     * @return the operator, or {@code null} when the step declares no grid shift
     */
    HGridShiftOperator hgridshift() {
        return hgridshift;
    }

    /** {@code P->vto_meter}, for tests and diagnostics. */
    double verticalToMeter() {
        return vToMeter;
    }

    @Override
    public String toString() {
        return "Cs2csOperator[" + description + ", " + kernel + ", left=" + left + ", right=" + right
                + (helmert != null ? ", helmert" : "") + (cart != null ? ", cart" : "")
                + (hgridshift != null ? ", " + hgridshift.description() : "")
                + (geoc ? ", geoc" : "")
                + (vgridshift != null ? ", " + vgridshift.description() : "")
                + (vToMeter != 1.0 ? ", vto_meter=" + vToMeter : "")
                + (axisSwap != null ? ", " + axisSwap.description() : "") + "]";
    }
}

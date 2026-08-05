/*******************************************************************************
 * Copyright 2009, 2017 Martin Davis
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
package org.locationtech.proj4j.parser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.locationtech.proj4j.*;
import org.locationtech.proj4j.datum.Datum;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.Grid;
import org.locationtech.proj4j.proj.AiryProjection;
import org.locationtech.proj4j.proj.CassiniProjection;
import org.locationtech.proj4j.proj.ColombiaUrbanProjection;
import org.locationtech.proj4j.proj.EquidistantAzimuthalProjection;
import org.locationtech.proj4j.proj.ExtendedTransverseMercatorProjection;
import org.locationtech.proj4j.proj.FoucautSinusoidalProjection;
import org.locationtech.proj4j.proj.GeneralSinusoidalProjection;
import org.locationtech.proj4j.proj.HammerProjection;
import org.locationtech.proj4j.proj.InternationalMapOfTheWorldPolyconicProjection;
import org.locationtech.proj4j.proj.LabordeProjection;
import org.locationtech.proj4j.proj.LagrangeProjection;
import org.locationtech.proj4j.proj.LandsatProjection;
import org.locationtech.proj4j.proj.MisrSpaceObliqueMercatorProjection;
import org.locationtech.proj4j.proj.ObliqueCylindricalEqualAreaProjection;
import org.locationtech.proj4j.proj.ObliqueMercatorProjection;
import org.locationtech.proj4j.proj.ObliqueTransformationProjection;
import org.locationtech.proj4j.proj.PeirceQuincuncialProjection;
import org.locationtech.proj4j.proj.Projection;
import org.locationtech.proj4j.proj.SpaceObliqueMercatorProjection;
import org.locationtech.proj4j.proj.SpilhausProjection;
import org.locationtech.proj4j.proj.TiltedPerspectiveProjection;
import org.locationtech.proj4j.proj.TransverseMercatorProjection;
import org.locationtech.proj4j.proj.TwoPointEquidistantProjection;
import org.locationtech.proj4j.proj.Urmaev5Projection;
import org.locationtech.proj4j.proj.UrmaevFlatPolarSinusoidalProjection;
import org.locationtech.proj4j.units.Angle;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;
import org.locationtech.proj4j.util.ProjectionMath;

public class Proj4Parser {

    /**
     * Controls how far parsing goes beyond what PROJ itself rejects.
     */
    public enum ParseMode {
        /**
         * Behave like PROJ: unrecognised keys are retained and ignored rather
         * than being errors, and an unknown {@code +units} name falls back to
         * metres as it always has in PROJ4J. Only <i>values</i> of recognised
         * keys are validated.
         */
        PROJ_COMPATIBLE,

        /**
         * Additionally rejects things PROJ tolerates: keys outside
         * {@link Proj4Keyword#supportedParameters()} and {@code +units} names
         * this library cannot resolve.
         */
        STRICT
    }

    private final Registry registry;
    private final ParseMode mode;

    public Proj4Parser(Registry registry) {
        this(registry, ParseMode.PROJ_COMPATIBLE);
    }

    public Proj4Parser(Registry registry, ParseMode mode) {
        this.registry = registry;
        this.mode = mode;
    }

    public ParseMode getParseMode() {
        return mode;
    }

    /**
     * Parses a whitespace-separated PROJ.4 parameter string.
     * <p>
     * Tokenised with the same {@code split("\\s+")} {@link org.locationtech.proj4j.CRSFactory}
     * has always used, so the two entry points cannot drift: a leading separator yields an
     * empty first token, which {@link #createParameterMap(String[])} skips exactly as it
     * skips one in a caller-supplied array.
     * <p>
     * This overload exists so that a caller wanting a non-default {@link ParseMode} does not
     * have to reimplement the tokenisation. {@code CRSFactory} is deliberately <b>not</b>
     * routed through it: the 1.x API is frozen at {@link ParseMode#PROJ_COMPATIBLE}.
     *
     * @param name     a name for the CRS, or null for an anonymous one
     * @param paramStr the parameter string, or null
     * @return the CRS, or null if {@code paramStr} is null
     * @since 1.5.0
     */
    public CoordinateReferenceSystem parse(String name, String paramStr) {
        if (paramStr == null)
            return null;
        return parse(name, paramStr.split("\\s+"));
    }

    public CoordinateReferenceSystem parse(String name, String[] args) {
        if (args == null)
            return null;

        Map<String, String> params = createParameterMap(args);
        if (mode == ParseMode.STRICT) {
            Proj4Keyword.checkUnsupported(params.keySet());
        }
        DatumParameters datumParam = new DatumParameters();
        parseDatum(params, datumParam);
        parseEllipsoid(params, datumParam);
        Datum datum = datumParam.getDatum();
        // NOTE: datum must never be mutated here.  datumParam.getDatum() may
        // return one of the shared Datum singletons (Datum.NAD27 etc.), and
        // calling setGrids() on it would corrupt it process-wide - which is
        // what used to destroy NAD27's grid list on the first parse of
        // EPSG:4267.  DatumParameters.getDatum() derives a new Datum instead.
        Ellipsoid ellipsoid = datumParam.isSphere()
                ? datumParam.getEllipsoid()
                : datum.getEllipsoid();
        // TODO: this makes a difference - why?
        // which is better?
//    Ellipsoid ellipsoid = datumParam.getEllipsoid();
        Projection proj = parseProjection(params, ellipsoid, args);
        return new CoordinateReferenceSystem(name, args, datum, proj);
    }

    /**
     * Creates a {@link Projection}
     * initialized from a PROJ.4 argument list.
     *
     * @param params the argument list as a key/value map, first occurrence winning
     * @param ellipsoid the resolved ellipsoid
     * @param args the <b>raw</b> argument list. Needed by {@code +proj=ob_tran} and by
     *        nothing else: {@code ob_tran_target_params} rewrites {@code o_proj=xxx} into
     *        {@code proj=xxx} by advancing the token pointer two characters
     *        ({@code ob_tran.cpp:159}) and passes the whole list to the child's
     *        initialiser, so it needs the list and not the map. May be {@code null} for a
     *        caller that has only a map, in which case an {@code ob_tran} definition
     *        fails with {@link ErrorCause#MISSING_PARAM} rather than silently losing its
     *        child.
     */
    private Projection parseProjection(Map<String, String> params, Ellipsoid ellipsoid,
            String[] args) {
        Projection projection = null;

        String s;
        s = params.get(Proj4Keyword.proj);
        if (s != null) {
            projection = registry.getProjection(s);
            if (projection == null)
                throw new InvalidValueException("Unknown projection: " + s);
        }
		else {
			throw new InvalidValueException("Keyword '" + Proj4Keyword.proj + "' is a required parameter");
        }
        projection.setEllipsoid(ellipsoid);

        //TODO: better error handling for things like bad number syntax.
        // Should be able to report the original param string in the error message
        // Should the exception be lib-specific?  (e.g. ParseException)

        s = params.get(Proj4Keyword.alpha);
        if (s != null)
            projection.setAlphaDegrees(parseAngle(Proj4Keyword.alpha, s));

        s = params.get(Proj4Keyword.lonc);
        if (s != null)
            projection.setLonCDegrees(parseAngle(Proj4Keyword.lonc, s));

        s = params.get(Proj4Keyword.lat_0);
        if (s != null)
            projection.setProjectionLatitudeDegrees(parseAngle(Proj4Keyword.lat_0, s));

        s = params.get(Proj4Keyword.lon_0);
        if (s != null)
            projection.setProjectionLongitudeDegrees(parseAngle(Proj4Keyword.lon_0, s));

        s = params.get(Proj4Keyword.lat_1);
        if (s != null)
            projection.setProjectionLatitude1Degrees(parseAngle(Proj4Keyword.lat_1, s));

        s = params.get(Proj4Keyword.lat_2);
        if (s != null)
            projection.setProjectionLatitude2Degrees(parseAngle(Proj4Keyword.lat_2, s));

        s = params.get(Proj4Keyword.lat_ts);
        if (s != null)
            projection.setTrueScaleLatitudeDegrees(parseAngle(Proj4Keyword.lat_ts, s));

        s = params.get(Proj4Keyword.x_0);
        if (s != null)
            projection.setFalseEasting(parseDouble(Proj4Keyword.x_0, s));

        s = params.get(Proj4Keyword.y_0);
        if (s != null)
            projection.setFalseNorthing(parseDouble(Proj4Keyword.y_0, s));

        // PROJ checks k_0 after k, so k_0 wins when both are given
        s = params.get(Proj4Keyword.k_0);
        if (s == null)
            s = params.get(Proj4Keyword.k);
        if (s != null)
            projection.setScaleFactor(parseDouble(Proj4Keyword.k_0, s));
        /*
         * omerc tests +no_off and +no_uoff with the same "t" (presence) sigil and ORs
         * them (omerc.cpp:139-144), so they are synonyms; only the second was
         * recognised here, which silently dropped the spelling the documentation uses.
         */
        if (params.containsKey(Proj4Keyword.no_uoff) || params.containsKey(Proj4Keyword.no_off))
            projection.setNoUoff(true);
        s = params.get(Proj4Keyword.gamma);
        if (s != null)
            projection.setGammaDegrees(parseAngle(Proj4Keyword.gamma, s));

        /*
         * The linear unit, resolved exactly once, the way init.cpp:678-714 does it:
         *
         *     if (+units given)  -> look it up; that entry's own to_meter string is
         *                           what gets parsed, and +to_meter is NEVER READ
         *     else if (+to_meter) -> parse it, ratio syntax included
         *     else                -> 1
         *
         * PROJ4J used to apply +units and then overwrite it with +to_meter, so
         * +to_meter won - exactly inverted. Verified against 9.8.1:
         * "+proj=merc +ellps=GRS80 +to_meter=0.3048 +units=us-ft" and the same
         * pair in the other order both give 730441.392088531, i.e. the US foot,
         * not the international one (730442.852974236).
         *
         * Neither +units nor +to_meter changes what +x_0/+y_0/+a/+b mean: those
         * are always metres. The output affine is fr_meter * (a*x + x_0)
         * (fwd.cpp:143-146), which is what Projection.initialize()'s
         * totalScale/totalFalseEasting already compute - so nothing here scales
         * a false easting on the way in.
         */
        String unitsName = params.get(Proj4Keyword.units);
        if (unitsName != null) {
            Unit unit = findUnits(unitsName);
            if (unit == null) {
                // Units.findUnits() never returns null - it falls back to
                // metres for any unknown name - so this is only reachable
                // through findUnits() below, and only in STRICT mode.
                throw new InvalidValueException("Unknown unit: " + unitsName);
            }
            projection.setFromMetres(1.0 / unit.value);
            projection.setUnits(unit);
        } else {
            s = params.get(Proj4Keyword.to_meter);
            if (s != null)
                projection.setFromMetres(1.0 / parseToMeter(Proj4Keyword.to_meter, s));
        }

        s = params.get(Proj4Keyword.h);
        if (s != null) {
            projection.setHeightOfOrbit(parseDouble(Proj4Keyword.h, s));
        }

        /*
         * +h_0 is col_urban's and nothing else's (col_urban.cpp:65,
         * pj_param(ctx, P->params, "dh_0").f), so it is dispatched on the concrete
         * class the way +zone and +shape are. Distinct from +h, the satellite height
         * of geos/nsper/tpers handled just above; always metres, never scaled by
         * +units.
         */
        if (projection instanceof ColombiaUrbanProjection) {
            s = params.get(Proj4Keyword.h_0);
            if (s != null)
                ((ColombiaUrbanProjection) projection)
                        .setH0(parseDouble(Proj4Keyword.h_0, s));
        }

        if (params.containsKey(Proj4Keyword.south))
            projection.setSouthernHemisphere(true);

        /*
         * +over is global, not operator-scoped: fwd_prepare skips both of its adjlon
         * calls when it is set (fwd.cpp:82-83, :110-111) and inv_finalize skips its one
         * (inv.cpp:115-116). Hence Projection.setOver rather than a concrete class.
         *
         * Read with pj_param's 'b' sigil (init.cpp:601, "bover"), so a bare +over is true
         * and +over=f is explicitly false - hence parseBoolean and not containsKey. The
         * flag is consulted per coordinate, not by initialize(), so its position here is
         * free; it sits with +south because both are frame-level switches.
         */
        if (params.containsKey(Proj4Keyword.over))
            projection.setOver(parseBoolean(Proj4Keyword.over, params.get(Proj4Keyword.over)));

        s = params.get(Proj4Keyword.pm);
        if (s != null)
            projection.setPrimeMeridian(normalizePrimeMeridian(s));

        s = params.get(Proj4Keyword.axis);
        if (s != null)
            projection.setAxisOrder(s);

        /*
         * +R is NOT handled here.  It is a size parameter of the ellipsoid and
         * is resolved in parseEllipsoid(), where it declares a sphere
         * (es = 0) exactly as ell_set.cpp:92-100 does.  The old
         * Projection.setRadius(...) call assigned the semi-major axis only,
         * leaving e/es from the previous ellipsoid, so the ellipsoidal formula
         * ran on a declared sphere.
         */

        //TODO: implement some of these parameters ?

        // this must be done last, since behaviour depends on other params being set (eg +south)
        if (projection instanceof TransverseMercatorProjection) {
            TransverseMercatorProjection tmerc = (TransverseMercatorProjection) projection;

            /*
             * +proj=utm requires an ellipsoid. PJ_PROJECTION(utm) opens with
             *
             *     if (P->es == 0.0) { "Invalid value for eccentricity: it should not be
             *                          zero"; PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE }
             *
             * (tmerc.cpp), whereas PJ_PROJECTION(tmerc) is happy on a sphere and
             * dispatches to the spherical formulation. Verified against the installed
             * 9.8.1: "+proj=utm +zone=32 +a=6400000" and "+proj=utm +zone=32 +R=6400000"
             * both fail with that message, while "+proj=tmerc +R=6400000" projects
             * (12, 56) to (747461.594362, 6320539.671392).
             *
             * Note the condition is "the operation is utm", NOT "+zone was given":
             * upstream's check is the first statement of the utm entry point and runs
             * before +zone is even looked at, so "+proj=utm +a=6400000" with the zone
             * derived from +lon_0 fails too. Keying this on +zone would let that case
             * through - and, before utm was bound to this class, it was a
             * NullPointerException rather than any kind of reported error.
             */
            if ("utm".equals(params.get(Proj4Keyword.proj)) && ellipsoid.getEccentricitySquared() == 0.0) {
                throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                        "+proj=utm: Invalid value for eccentricity: it should not be zero. "
                                + "UTM is defined on an ellipsoid; use +proj=tmerc for a sphere.");
            }

            /*
             * +approx and +algo select the algorithm and are read by tmerc/utm alone.
             * Both setters take effect without a re-initialize(), and +approx is tested
             * before +algo and wins over it, exactly as getAlgoFromParams does
             * (tmerc.cpp:548-552). setAlgorithm raises INVALID_PARAM_VALUE for an
             * unrecognised value itself, so nothing is validated twice here.
             *
             * Set before +zone, because setUTMZone re-runs initialize().
             */
            if (params.containsKey(Proj4Keyword.approx)
                    && parseBoolean(Proj4Keyword.approx, params.get(Proj4Keyword.approx))) {
                tmerc.setApprox(true);
            }
            s = params.get(Proj4Keyword.algo);
            if (s != null)
                tmerc.setAlgorithm(s);

            s = params.get(Proj4Keyword.zone);
            if (s != null)
                tmerc.setUTMZone(parseInt(Proj4Keyword.zone, s));
        }
        if (projection instanceof ExtendedTransverseMercatorProjection) {
            s = params.get(Proj4Keyword.zone);
            if (s != null)
                ((ExtendedTransverseMercatorProjection) projection).setUTMZone(parseInt(Proj4Keyword.zone, s));
        }
        /*
         * +shape / +scrollx / +scrolly are read by peirce_q and by nothing else
         * (adams.cpp:405-453), so they are dispatched on the concrete class in the
         * same way +zone is above rather than through a setter on Projection.
         *
         * A bare "+shape" with no value is an error, not a no-op: pj_param type 's'
         * yields the empty string for a valueless token (param.cpp:174-192), which
         * matches none of the six names, so pj_adams_setup fails. Silently keeping
         * the default there would be the same silent-wrong-answer this dispatch
         * exists to remove, so containsKey is tested rather than get() != null.
         */
        if (projection instanceof PeirceQuincuncialProjection) {
            PeirceQuincuncialProjection peirceQ = (PeirceQuincuncialProjection) projection;
            if (params.containsKey(Proj4Keyword.shape)) {
                s = params.get(Proj4Keyword.shape);
                peirceQ.setShape(s == null ? "" : s);
            }
            /*
             * pj_adams_setup reads +scrollx only inside the "horizontal" branch and
             * +scrolly only inside the "vertical" branch (adams.cpp:420-447), so on
             * any other shape both are retained-and-ignored - and, because the range
             * check lives inside the same branch, an out-of-range value is not even
             * an error there. That looks like an upstream oversight; it is
             * nonetheless the behaviour the corpus was generated against, so it is
             * reproduced rather than regularised.
             */
            if (peirceQ.getShape() == PeirceQuincuncialProjection.Shape.HORIZONTAL) {
                s = params.get(Proj4Keyword.scrollx);
                if (s != null)
                    peirceQ.setScrollX(parseDouble(Proj4Keyword.scrollx, s));
            } else if (peirceQ.getShape() == PeirceQuincuncialProjection.Shape.VERTICAL) {
                s = params.get(Proj4Keyword.scrolly);
                if (s != null)
                    peirceQ.setScrollY(parseDouble(Proj4Keyword.scrolly, s));
            }
        }
        /*
         * +azi is read upstream by FOUR operators - spilhaus (spilhaus.cpp:133-136),
         * tpers (nsper.cpp:196), labrd (labrd.cpp:117) and isea (isea.cpp:1024) - and
         * +rot by spilhaus alone. All are the "r" sigil, i.e. dmstor, so they take DMS, a
         * trailing cardinal and the r/R radian suffix and never bare
         * Double.parseDouble.
         *
         * The fan-out below is load-bearing, not tidiness. While +azi reached
         * SpilhausProjection ONLY, `+proj=tpers +azi=20` would have parsed cleanly,
         * passed the STRICT allow-list and then dropped the azimuth - a silently
         * unrotated map, the same defect class as `+proj=peirce_q +shape=square`
         * projecting as a diamond. That is exactly why tpers was left out of Registry
         * until this dispatch existed, and why registering the two must never be split.
         * labrd was already registered with the same hole open; no corpus row exercises
         * `labrd +azi`, which is why it survived unnoticed.
         *
         * isea is not ported, so its +proj= name is refused before +azi could matter.
         *
         * All of these must be set before initialize(), which derives cosrot/sinrot,
         * lambda_0 and beta (spilhaus), cg/sg/cw/sw (tpers) and Ca/Cb/Cc/Cd (labrd)
         * from them.
         */
        if (projection instanceof SpilhausProjection) {
            SpilhausProjection spilhaus = (SpilhausProjection) projection;
            s = params.get(Proj4Keyword.azi);
            if (s != null)
                spilhaus.setAziDegrees(parseAngle(Proj4Keyword.azi, s));

            s = params.get(Proj4Keyword.rot);
            if (s != null)
                spilhaus.setRotDegrees(parseAngle(Proj4Keyword.rot, s));
        }
        /*
         * tpers is nsper with the image plane rotated out of the tangent plane. +tilt is
         * upstream's omega and +azi its gamma (nsper.cpp:186-187), both 'r' sigils and
         * both defaulting to 0; +h is inherited and handled above, and is still mandatory
         * because nsper_setup rejects h/a <= 0.
         *
         * parseAngleRadians rather than parseAngle, so an r-suffixed value is scaled
         * exactly once instead of by RTD and then by DTR again.
         */
        if (projection instanceof TiltedPerspectiveProjection) {
            TiltedPerspectiveProjection tpers = (TiltedPerspectiveProjection) projection;
            s = params.get(Proj4Keyword.azi);
            if (s != null)
                tpers.setAziRadians(parseAngleRadians(Proj4Keyword.azi, s));

            s = params.get(Proj4Keyword.tilt);
            if (s != null)
                tpers.setTiltRadians(parseAngleRadians(Proj4Keyword.tilt, s));
        }
        if (projection instanceof LabordeProjection) {
            s = params.get(Proj4Keyword.azi);
            if (s != null)
                ((LabordeProjection) projection)
                        .setAziRadians(parseAngleRadians(Proj4Keyword.azi, s));
        }

        /*
         * +W is read by TWO operators, not one: lagrng (lagrng.cpp:79-85, default 2) and
         * hammer (hammer.cpp:63-70, default .5), and hammer also reads +M
         * (hammer.cpp:72-79, default 1). All are plain 'd' doubles behind a 't' presence
         * test - not angles - and capital W/M with no lower-case synonyms. `<= 0` is a
         * hard error in each; each class's initialize() raises it, so nothing is
         * validated twice here.
         *
         * Dispatching +W to lagrng alone was MEASURED to be wrong, and it is worth
         * recording how: it made builtins.gie:2596's `+proj=hammer +a=6400000 +W=1`
         * executable with W silently dropped, and that row asserts `expect failure`
         * at (-180, 0) - where the W = 1 forward is singular but the W = .5 default
         * returns a plausible -18101933.598. The corpus's "failed to fail" count went
         * from 0 to 1 and that is how it surfaced. Grepping upstream for EVERY reader of
         * a key before registering it is the cheap version of this check:
         *
         *   git grep -nE 'pj_param[^"]*"[a-z]W"' 9.8.1 -- src/
         */
        if (projection instanceof LagrangeProjection) {
            s = params.get(Proj4Keyword.W);
            if (s != null)
                ((LagrangeProjection) projection).setW(parseDouble(Proj4Keyword.W, s));
        }
        if (projection instanceof HammerProjection) {
            HammerProjection hammer = (HammerProjection) projection;
            s = params.get(Proj4Keyword.W);
            if (s != null)
                hammer.setW(parseDouble(Proj4Keyword.W, s));

            s = params.get(Proj4Keyword.M);
            if (s != null)
                hammer.setM(parseDouble(Proj4Keyword.M, s));
        }

        /*
         * airy's two parameters, and airy's alone (airy.cpp:119-120). +no_cut is a 'b'
         * sigil, so +no_cut=f really is off; +lat_b is an 'r'. Both setters already
         * existed and were reachable only from Java, and initialize() derives Cb from
         * lat_b without writing either field - which is what makes them survive the
         * SECOND initialize() the parser triggers below.
         */
        if (projection instanceof AiryProjection) {
            AiryProjection airy = (AiryProjection) projection;
            if (params.containsKey(Proj4Keyword.no_cut))
                airy.setNoCut(parseBoolean(Proj4Keyword.no_cut,
                        params.get(Proj4Keyword.no_cut)));

            s = params.get(Proj4Keyword.lat_b);
            if (s != null)
                airy.setLatB(parseAngleRadians(Proj4Keyword.lat_b, s));
        }

        /*
         * +guam swaps aeqd's forward and inverse for the Guam variant (aeqd.cpp:301), a
         * 'b' sigil. Upstream tests it INSIDE the `es != 0` arm of PJ_PROJECTION(aeqd),
         * so on a declared sphere it is read, marked used and has no effect; that
         * placement is reproduced in EquidistantAzimuthalProjection.initialize() rather
         * than here, because whether the figure is a sphere is not known until the
         * ellipsoid has been applied.
         */
        if (projection instanceof EquidistantAzimuthalProjection) {
            if (params.containsKey(Proj4Keyword.guam))
                ((EquidistantAzimuthalProjection) projection).setGuam(
                        parseBoolean(Proj4Keyword.guam, params.get(Proj4Keyword.guam)));
        }

        /*
         * +hyperbolic is cass's Vanua Levu variant. Read with pj_param_exists
         * (cass.cpp:127) and NOT with the 'b' sigil, so presence is the whole test and
         * `+hyperbolic=f` is TRUE upstream - hence containsKey rather than parseBoolean.
         * That asymmetry with +no_cut/+guam/+over just above is upstream's, and it is the
         * kind of thing a "flags are booleans" generalisation gets wrong.
         */
        if (projection instanceof CassiniProjection) {
            if (params.containsKey(Proj4Keyword.hyperbolic))
                ((CassiniProjection) projection).setHyperbolic(true);
        }

        /*
         * lsat reads +lsat and +path, both 'i' sigils (som.cpp:307-320), and both through
         * parseIntStrict: their grammar is decimal digits and nothing else, so +path=12a
         * and +path=-5 are errors rather than 12 and -5.
         *
         * The class hard-coded land = 1 and path = 120 behind a //FIXME, so
         * `+proj=lsat +path=2` silently returned path 120's map. Upstream has no default
         * for either and 0 fails both range checks; see LandsatProjection for why 1/120
         * are kept as defaults here and what has to be re-pinned before they can become
         * 0.
         */
        if (projection instanceof LandsatProjection) {
            LandsatProjection lsat = (LandsatProjection) projection;
            s = params.get(Proj4Keyword.lsat);
            if (s != null)
                lsat.setLandsat(parseIntStrict(Proj4Keyword.lsat, s));

            s = params.get(Proj4Keyword.path);
            if (s != null)
                lsat.setPath(parseIntStrict(Proj4Keyword.path, s));
        }

        /*
         * +lon_1 / +lon_2 are read upstream by FOUR operators, all with pj_param's "r"
         * sigil and all as the second half of a two-point form whose latitudes come from
         * +lat_1/+lat_2:
         *
         *   omerc  (omerc.cpp:152-155)   lon_1 and lon_2
         *   ocea   (ocea.cpp:80-81)      lon_1 and lon_2, in the no-+alpha branch only
         *   tpeqd  (tpeqd.cpp:73,75)     lon_1 and lon_2
         *   imw_p  (imw_p.cpp:191-192)   lon_1 only; absent means "derive it from the
         *                                latitude", which is a DIFFERENT answer from 0
         *
         * +lat_1/+lat_2 were already dispatched through Projection, so all four could be
         * given their latitudes but not their longitudes. omerc's dispatch landed first;
         * the other three kept reading 0 silently while the conformance bridge listed
         * lon_1/lon_2 as honoured - i.e. it vouched for a key three of the four readers
         * dropped. builtins.gie's ocea blocks make that visible: `+lon_2=1e-8`,
         * `+lon_2=-1e-8` and `+lon_2=1e-5` select the east, west and north-east framings
         * and all three collapsed onto the north one.
         *
         * All four setters take RADIANS, so parseAngleRadians - which scales an
         * r-suffixed value once rather than by RTD and then by DTR again.
         */
        if (projection instanceof ObliqueMercatorProjection) {
            ObliqueMercatorProjection omerc = (ObliqueMercatorProjection) projection;
            s = params.get(Proj4Keyword.lon_1);
            if (s != null)
                omerc.setLon1(parseAngleRadians(Proj4Keyword.lon_1, s));

            s = params.get(Proj4Keyword.lon_2);
            if (s != null)
                omerc.setLon2(parseAngleRadians(Proj4Keyword.lon_2, s));

            /*
             * +no_rot is tested with the same "t" (presence) sigil as +no_off/+no_uoff
             * (omerc.cpp:145, pj_param(..., "tno_rot").i), so it is valueless: present
             * means "do not rotate the (u,v) frame".
             *
             * Deliberately dispatched here on the concrete class rather than beside
             * setNoUoff above, because setNoRot is ObliqueMercatorProjection's own and
             * Projection carries no such flag. It could not be dispatched at all until
             * the setter existed: `rot` was a private field assigned unconditionally in
             * initialize(), which runs TWICE - once from the constructor and once from
             * the parser - so anything a setter was told was discarded on the second
             * pass. The field is now a field initialiser and initialize() no longer
             * writes it.
             */
            if (params.containsKey(Proj4Keyword.no_rot))
                omerc.setNoRot(true);
        }
        if (projection instanceof ObliqueCylindricalEqualAreaProjection) {
            ObliqueCylindricalEqualAreaProjection ocea =
                    (ObliqueCylindricalEqualAreaProjection) projection;
            s = params.get(Proj4Keyword.lon_1);
            if (s != null)
                ocea.setLon1(parseAngleRadians(Proj4Keyword.lon_1, s));

            s = params.get(Proj4Keyword.lon_2);
            if (s != null)
                ocea.setLon2(parseAngleRadians(Proj4Keyword.lon_2, s));
        }
        if (projection instanceof TwoPointEquidistantProjection) {
            TwoPointEquidistantProjection tpeqd = (TwoPointEquidistantProjection) projection;
            s = params.get(Proj4Keyword.lon_1);
            if (s != null)
                tpeqd.setLon1(parseAngleRadians(Proj4Keyword.lon_1, s));

            s = params.get(Proj4Keyword.lon_2);
            if (s != null)
                tpeqd.setLon2(parseAngleRadians(Proj4Keyword.lon_2, s));
        }
        /*
         * imw_p reads +lon_1 and NOT +lon_2 (imw_p.cpp:191-192). Absence is not zero
         * there: the class holds NaN for "absent" and derives lam_1 from the latitude
         * instead, so this must stay a `!= null` guard and must never be given a 0
         * default.
         */
        if (projection instanceof InternationalMapOfTheWorldPolyconicProjection) {
            s = params.get(Proj4Keyword.lon_1);
            if (s != null)
                ((InternationalMapOfTheWorldPolyconicProjection) projection)
                        .setLon1(parseAngleRadians(Proj4Keyword.lon_1, s));
        }

        /*
         * +n reaches urmfps (urmfps.cpp:56-66), urm5 (urm5.cpp:36-47) and gn_sinu
         * (gn_sinu.cpp:180-198), and +m reaches gn_sinu alone. Dispatched on the
         * concrete class rather than through Projection because "n" and "m" are the two
         * most heavily overloaded letters in PROJ: each is a shape parameter of a dozen
         * unrelated operators and a unitless scale in the CRS parser (io.cpp:12520).
         *
         * Absence is a hard error upstream ("Missing parameter n." / "m."). That check
         * belongs in each projection's own initialize(), which already range-checks;
         * the parser's job is only to get the value there.
         *
         * All three keys are read with pj_param's 'd' sigil, so parseDouble and not
         * parseAngle. gn_sinu's dispatch is on GeneralSinusoidalProjection and NOT on
         * its package-private base: sinu/eck6/mbtfps share that base's kernel but
         * hard-code their own m and n and read neither key upstream, so
         * McBrydeThomasFlatPolarSinusoidalProjection must not receive them.
         */
        if (projection instanceof UrmaevFlatPolarSinusoidalProjection) {
            s = params.get(Proj4Keyword.n);
            if (s != null)
                ((UrmaevFlatPolarSinusoidalProjection) projection)
                        .setN(parseDouble(Proj4Keyword.n, s));
        }
        if (projection instanceof Urmaev5Projection) {
            Urmaev5Projection urm5 = (Urmaev5Projection) projection;
            s = params.get(Proj4Keyword.n);
            if (s != null)
                urm5.setN(parseDouble(Proj4Keyword.n, s));

            s = params.get(Proj4Keyword.q);
            if (s != null)
                urm5.setQ(parseDouble(Proj4Keyword.q, s));
        }
        if (projection instanceof GeneralSinusoidalProjection) {
            GeneralSinusoidalProjection gnSinu = (GeneralSinusoidalProjection) projection;
            s = params.get(Proj4Keyword.n);
            if (s != null)
                gnSinu.setN(parseDouble(Proj4Keyword.n, s));

            s = params.get(Proj4Keyword.m);
            if (s != null)
                gnSinu.setM(parseDouble(Proj4Keyword.m, s));
        }
        /*
         * fouc_s is the fourth reader of +n and was missing. Upstream reads it as "dn"
         * (fouc_s.cpp:61) and fouc_s IS registered (Registry), so without this branch
         * `+proj=fouc_s +n=0.5` parsed cleanly, was accepted by the STRICT allow-list,
         * and then silently used n = 0. Nothing was visibly wrong only because
         * builtins.gie:2050 is a bare `+proj=fouc_s +a=6400000` with no +n - i.e. the
         * corpus does not exercise the defect, which is exactly why it survived.
         *
         * This is the reason "n" was previously unsound in the conformance bridge's
         * HONOURED set: the bridge vouched that proj4j applies +n, and for fouc_s it did
         * not. The setter landed with the numerical work; this is its dispatch.
         */
        if (projection instanceof FoucautSinusoidalProjection) {
            s = params.get(Proj4Keyword.n);
            if (s != null)
                ((FoucautSinusoidalProjection) projection)
                        .setN(parseDouble(Proj4Keyword.n, s));
        }

        /*
         * som and misrsom share one file and one setup upstream (som.cpp), and
         * MisrSpaceObliqueMercatorProjection extends SpaceObliqueMercatorProjection
         * here, so the order of these two branches is load-bearing: misrsom derives
         * lam0/alf/p22 from +path and does NOT read +inc_angle/+ps_rev/+asc_lon
         * (som.cpp:287-306 never calls pj_param for them), so it must be tested first
         * and must not fall through.
         *
         * +inc_angle and +asc_lon are 'r' sigil - hence parseAngle, which takes DMS, a
         * trailing cardinal and the r/R radian suffix that two of the four corpus som
         * blocks actually use. +ps_rev is a plain 'd'. +path is an 'i', whose grammar is
         * digits and nothing else; see parseIntStrict.
         */
        if (projection instanceof MisrSpaceObliqueMercatorProjection) {
            s = params.get(Proj4Keyword.path);
            if (s != null)
                ((MisrSpaceObliqueMercatorProjection) projection)
                        .setPath(parseIntStrict(Proj4Keyword.path, s));
        } else if (projection instanceof SpaceObliqueMercatorProjection) {
            SpaceObliqueMercatorProjection som = (SpaceObliqueMercatorProjection) projection;
            s = params.get(Proj4Keyword.inc_angle);
            if (s != null)
                som.setIncidenceAngle(parseAngleRadians(Proj4Keyword.inc_angle, s));

            s = params.get(Proj4Keyword.ps_rev);
            if (s != null)
                som.setPeriodOfRevolution(parseDouble(Proj4Keyword.ps_rev, s));

            s = params.get(Proj4Keyword.asc_lon);
            if (s != null)
                som.setAscendingLongitude(parseAngleRadians(Proj4Keyword.asc_lon, s));
        }

        /*
         * ob_tran is the one operator that needs the raw argument list rather than
         * values out of the map, because ob_tran_target_params builds its child's argv
         * from the ob_tran argv: it drops "proj=ob_tran" and a bare "inv", then turns
         * the FIRST "o_proj=xxx" token into "proj=xxx" by advancing the pointer two
         * characters (ob_tran.cpp:159, args.argv[i] += 2) and stops. Everything else -
         * including +o_lat_p, which the child ignores, and +lon_0, which the child
         * parses and then never uses because it is invoked at its raw layer - passes
         * through verbatim. Hence one call rather than ten.
         *
         * setParameters also chooses among the three pole specifications by presence
         * (+o_alpha, else +o_lat_p, else the two-point form), which is why it takes the
         * list too: pj_param's 't' sigil tests presence and never value, so +o_alpha=0
         * selects the azimuth form and a bare +o_lat_p selects the pole form.
         *
         * Must run before initialize(), which requires a child and demotes the output
         * affine to the identity when that child is geographic.
         */
        if (projection instanceof ObliqueTransformationProjection) {
            if (args == null) {
                throw new InvalidValueException(ErrorCause.MISSING_PARAM,
                        "+proj=ob_tran needs its whole parameter list, not a parsed map, "
                                + "because ob_tran_target_params derives the child's argument "
                                + "list from it; this parse was given no argument array");
            }
            ((ObliqueTransformationProjection) projection).setParameters(args);
        }

        projection.initialize();

        return projection;
    }

    /**
     * Resolves a {@code +units} name, returning null when this library cannot
     * resolve it and the parse mode says that should be an error.
     * <p>
     * {@link Units#findUnits(String)} falls back to metres for any unknown
     * name and never returns null, so the unknown case has to be detected by
     * checking whether metres was actually asked for.
     */
    private Unit findUnits(String name) {
        Unit unit = Units.findUnits(name);
        if (mode != ParseMode.STRICT)
            return unit;
        return Units.isKnownUnit(name) ? unit : null;
    }

    /**
     * PROJ accepts either one of its named prime meridians or an angle in any
     * of its angular syntaxes (DMS, a cardinal suffix, an {@code r}/{@code R}
     * radian suffix). PROJ4J's {@code PrimeMeridian.forName} only understands a
     * plain decimal number, so an angular value is normalised to decimal
     * degrees here and a name is passed through untouched.
     */
    private static String normalizePrimeMeridian(String s) {
        try {
            return Double.toString(parseAngle(Proj4Keyword.pm, s));
        } catch (Proj4jException e) {
            // not an angle - treat it as a meridian name
            return s;
        }
    }

    private void parseDatum(Map<String, String> params, DatumParameters datumParam) {
        String towgs84 = params.get(Proj4Keyword.towgs84);
        if (towgs84 != null) {
            double[] datumConvParams = parseToWGS84(towgs84);
            datumParam.setDatumTransform(datumConvParams);
        }

        String code = params.get(Proj4Keyword.datum);
        if (code != null) {
            Datum datum = registry.getDatum(code);
            if (datum == null)
                throw new InvalidValueException("Unknown datum: " + code);
            datumParam.setDatum(datum);
        }

        String nadgrids = params.get(Proj4Keyword.nadgrids);
        if (nadgrids != null) {
            try {
                datumParam.setGrids(Grid.fromNadGrids(nadgrids));
            } catch (IOException e) {
                throw new InvalidValueException("Unknown nadgrid: " + nadgrids, e);
            }
        }
    }

    private double[] parseToWGS84(String paramList) {
        String[] numStr = paramList.split(",");

        if (!(numStr.length == 3 || numStr.length == 7)) {
            throw new InvalidValueException("Invalid number of values (must be 3 or 7) in +towgs84: " + paramList);
        }
        double[] param = new double[numStr.length];
        for (int i = 0; i < numStr.length; i++) {
            param[i] = parseDouble(Proj4Keyword.towgs84, numStr[i]);
        }
        if (param.length > 3) {
            // optimization to detect 3-parameter transform
            if (param[3] == 0.0
                    && param[4] == 0.0
                    && param[5] == 0.0
                    && param[6] == 0.0
                    ) {
                param = new double[]{param[0], param[1], param[2]};
            }
        }

        // NOTE: proj.4 adjusts the units of parameters 3-6 during parsing and
        // maintains "well-known" datum parameters as strings which also go through
        // the parsing routine.  In Proj4J we keep well-known datums in full-fledged
        // Datum instances so this is handled in the Datum class itself.

        return param;
    }

    /**
     * Resolves the ellipsoid, following PROJ 9.8.1 {@code pj_ellipsoid}
     * ({@code src/ell_set.cpp:80-133}) step for step:
     * <ol>
     * <li>{@code +R} present: size only, {@code es = f = e = rf = 0},
     *     {@code b = a}, <b>done</b> - every shape and spherification
     *     parameter is ignored.
     * <li>{@code ellps_ellps()}: {@code +ellps=xxx} seeds both size and shape.
     * <li>{@code ellps_size()}: {@code +a} overrides the size.
     * <li>{@code ellps_shape()}: the <i>first</i> present of
     *     {@code rf, f, es, e, b} overrides the shape - and only that one.
     * <li>{@code pj_calc_ellipsoid_params()}: validate.
     * <li>{@code ellps_spherification()}: the first present of
     *     {@code R_A, R_V, R_a, R_g, R_h, R_lat_a, R_lat_g, R_C} turns the
     *     ellipsoid into a sphere.
     * </ol>
     * A later shape parameter alongside {@code +ellps} is <b>not</b> a
     * contradiction; {@code ell_set.cpp}'s own comment documents it as a
     * deliberate modifier ({@code +ellps=GRS80 +a=1} being the motivating
     * example), so it is accepted here too.
     */
    private void parseEllipsoid(Map<String, String> params, DatumParameters datumParam) {
        /*
         * Specifying R overrules everything (ell_set.cpp:92-100).
         */
        String s = params.get(Proj4Keyword.R);
        if (s != null) {
            datumParam.setR(parseDouble(Proj4Keyword.R, s));
            return;
        }

        String code = params.get(Proj4Keyword.ellps);
        if (code != null) {
            Ellipsoid ellipsoid = registry.getEllipsoid(code);
            if (ellipsoid == null)
                throw new InvalidValueException("Unknown ellipsoid: " + code);
            datumParam.setEllipsoid(ellipsoid);
        }

        /*
         * Explicit parameters override ellps and datum settings
         */
        s = params.get(Proj4Keyword.a);
        if (s != null) {
            datumParam.setA(parseDouble(Proj4Keyword.a, s));
        }

        /*
         * ellps_shape(): exactly one shape parameter takes effect, selected by
         * the fixed order rf, f, es, e, b - the first one present breaks the
         * loop.  PROJ4J used to apply all of them cumulatively, last one
         * winning.
         */
        boolean shapeGiven = false;
        for (String key : Proj4Keyword.SHAPE_PARAMS) {
            s = params.get(key);
            if (s == null)
                continue;
            // +datum= makes PROJ append the datum's ellps, which supplies the
            // semi-major axis; a bare shape parameter does not.
            datumParam.seedSizeFromDatum();
            // ellps_size() runs first and errors out before ellps_shape() ever
            // sees the value.
            datumParam.requireSize();
            double value = parseDouble(key, s);
            if (Proj4Keyword.rf.equals(key)) {
                datumParam.setRF(value);
            } else if (Proj4Keyword.f.equals(key)) {
                datumParam.setF(value);
            } else if (Proj4Keyword.es.equals(key)) {
                datumParam.setES(value);
            } else if (Proj4Keyword.e.equals(key)) {
                datumParam.setE(value);
            } else {
                // +b needs the semi-major axis; PROJ errors with
                // "Major axis not given" in ellps_size(), before it ever gets
                // here.  PROJ4J used to compute 1 - b*b/NaN*NaN = NaN and then
                // silently fall through to Datum.WGS84.
                datumParam.setB(value);
            }
            shapeGiven = true;
            break;
        }

        if (!shapeGiven && code == null && datumParam.isSizeGiven()) {
            // "Not giving a shape parameter means selecting a sphere"
            // (ellps_shape).  +a on its own therefore describes a sphere, not
            // WGS84.
            datumParam.setSphericalShape();
        }

        datumParam.validateEllipsoid();

        parseEllipsoidModifiers(params, datumParam);
    }

    /**
     * Applies the {@code ellps_spherification} step: the first present of
     * {@code R_A, R_V, R_a, R_g, R_h, R_lat_a, R_lat_g, R_C} and no other.
     */
    private void parseEllipsoidModifiers(Map<String, String> params, DatumParameters datumParam) {
        String key = null;
        String value = null;
        for (String candidate : Proj4Keyword.SPHERIFICATION_PARAMS) {
            if (params.containsKey(candidate)) {
                key = candidate;
                value = params.get(candidate);
                break;
            }
        }
        if (key == null)
            return;

        // Spherification keys are not in the list that suppresses PROJ's
        // implicit "+ellps=GRS80" (init.cpp:317-360), so PROJ still has a full
        // ellipsoid to spherify at this point.
        datumParam.seedDefaultSize();

        if (Proj4Keyword.R_A.equals(key)) {
            datumParam.setR_A();
        } else if (Proj4Keyword.R_V.equals(key)) {
            datumParam.setR_V();
        } else if (Proj4Keyword.R_a.equals(key)) {
            datumParam.setR_a();
        } else if (Proj4Keyword.R_g.equals(key)) {
            datumParam.setR_g();
        } else if (Proj4Keyword.R_h.equals(key)) {
            datumParam.setR_h();
        } else if (Proj4Keyword.R_lat_a.equals(key)) {
            datumParam.setR_lat_a(requiredAngleRadians(key, value));
        } else if (Proj4Keyword.R_lat_g.equals(key)) {
            datumParam.setR_lat_g(requiredAngleRadians(key, value));
        } else {
            /*
             * +R_C is taken at phi0 -- and phi0 is ALWAYS ZERO here, whatever +lat_0 says.
             *
             * ell_set.cpp:438-452 reads P->phi0, and its comment says the radius is "taken at a
             * latitude that is phi0 (note: at least for mercator...)". But pj_init calls
             * pj_ellipsoid at init.cpp:566 and does not assign
             * `PIN->phi0 = pj_param(ctx, start, "rlat_0").f` until init.cpp:651, 85 lines later,
             * on a calloc'd PJ. So at spherification time P->phi0 is 0 and +R_C reduces to
             *
             *     a *= sqrt(1 - es) / (1 - es * sin^2(0)) = a * sqrt(1 - es) = b
             *
             * i.e. the semi-MINOR axis. The comment describes an intent the code does not
             * implement, and the corpus was generated by the code:
             * builtins.gie:4350 is `+proj=merc +R_C +ellps=WGS84 +lat_0=45` at (2, 49) and
             * expects 221892.515234695253, which is 6356752.314245179 * 2 deg -- WGS84's b, to
             * every printed digit. Honouring +lat_0=45 gives 6378101.030201018 and
             * 222637.726003700, off by 745 m.
             *
             * Reading +lat_0 here is therefore MORE correct and WRONG: this is the
             * "port verbatim where upstream's expected values were generated by upstream's own
             * behaviour" case, like adams.cpp's ell_int_5 Chebyshev series. Do not "fix" it
             * without re-pinning that row against a PROJ release that has fixed it upstream.
             *
             * DatumParameters.setR_C(phi0) keeps its parameter: the formula is right, only the
             * value pj_init supplies for it is 0. Callers outside the proj-string path may
             * legitimately want the conformal radius at a real latitude.
             */
            datumParam.setR_C(0.0);
        }
    }

    private static double requiredAngleRadians(String key, String value) {
        if (value == null)
            throw new InvalidValueException("Missing value for +" + key);
        return parseAngle(key, value) * ProjectionMath.DTR;
    }

    /**
     * Splits the argument list into a key/value map.
     * <p>
     * Two PROJ semantics are load-bearing here. Insertion order is preserved,
     * and <b>the first occurrence of a duplicated key wins</b>:
     * {@code pj_param_exists} walks the parameter list front-to-back and
     * returns the first match, and {@code +init=}/{@code +datum=} expansions
     * are appended, so user tokens shadow them. A {@code HashMap} kept the
     * <i>last</i> occurrence, exactly inverted.
     */
    private Map<String, String> createParameterMap(String[] args) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null)
                continue;
            // strip leading "+" if any
            if (arg.startsWith("+")) {
                arg = arg.substring(1);
            }
            if (arg.length() == 0)
                continue;
            int index = arg.indexOf('=');
            String key;
            String value;
            if (index != -1) {
                // param of form pppp=vvvv
                key = arg.substring(0, index);
                value = arg.substring(index + 1);
            } else {
                // param of form ppppp
                key = arg;
                value = null;
            }
            if (!params.containsKey(key)) {
                params.put(key, value);
            }
        }
        return params;
    }

    /**
     * Parses an angular parameter value, in degrees.
     * <p>
     * Accepts everything {@link Angle#parse(String)} does (decimal degrees,
     * DMS, a trailing cardinal) plus PROJ's {@code r}/{@code R} radian suffix,
     * which is legal on every angular parameter.
     */
    private static double parseAngle(String key, String s) {
        try {
            int length = s.length();
            if (length > 1) {
                char last = s.charAt(length - 1);
                if (last == 'r' || last == 'R') {
                    return Double.parseDouble(s.substring(0, length - 1)) * ProjectionMath.RTD;
                }
            }
            return Angle.parse(s);
        } catch (NumberFormatException e) {
            throw new InvalidValueException("Invalid value for +" + key + ": " + s, e);
        }
    }

    /**
     * {@code pj_param}'s {@code r} sigil in full: the same grammar as
     * {@link #parseAngle(String, String)} but returning <b>radians</b>, which is what
     * {@code dmstor_ctx} returns and what every {@code r}-sigil consumer stores.
     * <p>
     * Not a cosmetic difference. {@code parseAngle} answers in degrees, so an
     * {@code r}-suffixed value reaching a radian-valued field through it is multiplied by
     * {@code RTD} and then by {@code DTR} again - a round trip that costs about 1 ulp of the
     * angle. On {@code builtins.gie}'s {@code +proj=som +inc_angle=1.7157253262878522r} block
     * that showed up as a 0.02 mm easting shift against the same block written in decimal
     * degrees, inside the corpus's 0.1 mm tolerance but visible and avoidable. Here the radian
     * form is returned untouched and the degree form is scaled exactly once, by {@code DTR},
     * which is the same single multiplication {@code dmstor} performs.
     */
    private static double parseAngleRadians(String key, String s) {
        try {
            int length = s.length();
            if (length > 1) {
                char last = s.charAt(length - 1);
                if (last == 'r' || last == 'R') {
                    return Double.parseDouble(s.substring(0, length - 1));
                }
            }
            return Angle.parse(s) * ProjectionMath.DTR;
        } catch (NumberFormatException e) {
            throw new InvalidValueException("Invalid value for +" + key + ": " + s, e);
        }
    }

    private static double parseDouble(String key, String s) {
        if (s == null)
            throw new InvalidValueException("Missing value for +" + key);
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new InvalidValueException("Invalid value for +" + key + ": " + s, e);
        }
    }

    /**
     * Parses a {@code +to_meter}/{@code +vto_meter} value, which PROJ accepts either
     * as a plain double or as a {@code num/den} ratio: {@code init.cpp:690-710} runs
     * {@code pj_strtod}, and if parsing stopped on a {@code '/'} it divides by the
     * denominator. Its own unit table uses that syntax ({@code dm} is {@code "1/10"},
     * {@code us-in} is {@code "1/39.37"}), so it is not an exotic corner.
     *
     * @throws InvalidValueException on a zero denominator ("Invalid value for
     *                               to_meter donominator" upstream, {@code sic}) or a
     *                               non-positive result
     */
    private static double parseToMeter(String key, String s) {
        if (s == null)
            throw new InvalidValueException("Missing value for +" + key);
        String v = s.trim();
        int slash = v.indexOf('/');
        double value;
        if (slash >= 0) {
            double numerator = parseDouble(key, v.substring(0, slash));
            double denominator = parseDouble(key, v.substring(slash + 1));
            if (denominator == 0.0)
                throw new InvalidValueException("Invalid value for +" + key + " denominator: " + s);
            value = numerator / denominator;
        } else {
            value = parseDouble(key, v);
        }
        if (!(value > 0.0) || Double.isInfinite(value))
            throw new InvalidValueException("Invalid value for +" + key + ": " + s + ". Should be > 0");
        return value;
    }

    /**
     * {@code pj_param}'s {@code b} sigil ({@code param.cpp}): an absent value or
     * {@code T}/{@code t} is true, {@code F}/{@code f} is false, and anything else is an
     * error rather than a silent default.
     */
    private static boolean parseBoolean(String key, String s) {
        if (s == null || s.length() == 0 || "T".equals(s) || "t".equals(s))
            return true;
        if ("F".equals(s) || "f".equals(s))
            return false;
        throw new InvalidValueException("Invalid value for +" + key + ": " + s
                + ". Should be empty, T/t or F/f");
    }

    private static int parseInt(String key, String s) {
        if (s == null)
            throw new InvalidValueException("Missing value for +" + key);
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new InvalidValueException("Invalid value for +" + key + ": " + s, e);
        }
    }

    /**
     * {@code pj_param}'s {@code i} sigil, exactly ({@code param.cpp:180-187}):
     *
     * <pre>
     *   value.i = atoi(opt);
     *   for (const char *ptr = opt; *ptr != '\0'; ++ptr)
     *       if (!(*ptr &gt;= '0' &amp;&amp; *ptr &lt;= '9')) {
     *           proj_context_errno_set(ctx, PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE);
     *           value.i = 0;
     *       }
     * </pre>
     *
     * <p>So the grammar is <b>one or more decimal digits and nothing else</b>. A sign, a
     * decimal point, surrounding whitespace and any trailing text are all errors, not
     * partial parses: {@code +path=12a} is an error and {@code +path=-5} is an error too.
     * {@code Integer.parseInt} accepts both of the latter forms, which is why this is a
     * separate method rather than a widening of {@link #parseInt}.
     *
     * <p>{@link #parseInt} keeps its looser grammar for {@code +zone}, whose only caller
     * is {@code setUTMZone} and whose behaviour is not being changed here.
     *
     * @throws InvalidValueException with {@link ErrorCause#INVALID_PARAM_VALUE} for
     *         anything outside that grammar
     */
    private static int parseIntStrict(String key, String s) {
        if (s == null || s.length() == 0)
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "Missing value for +" + key + ". PROJ reads it with pj_param's 'i' sigil, "
                            + "whose grammar is one or more decimal digits");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9')
                throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                        "Invalid value for +" + key + ": " + s + ". PROJ reads it with "
                                + "pj_param's 'i' sigil, which rejects any character outside "
                                + "0-9 -- a sign, a decimal point or trailing text included");
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            // Only reachable for a digit string that overflows an int.
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    "Invalid value for +" + key + ": " + s + " does not fit in an int");
        }
    }

}

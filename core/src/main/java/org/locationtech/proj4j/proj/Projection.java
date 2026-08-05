/*******************************************************************************
 * Copyright 2006, 2017 Jerry Huxtable, Martin Davis
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

package org.locationtech.proj4j.proj;

import java.util.Objects;

import org.locationtech.proj4j.*;
import org.locationtech.proj4j.datum.AxisOrder;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.datum.PrimeMeridian;
import org.locationtech.proj4j.units.AngleFormat;
import org.locationtech.proj4j.units.Unit;
import org.locationtech.proj4j.units.Units;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * A map projection is a mathematical algorithm
 * for representing a spheroidal surface
 * on a plane.
 * A single projection
 * defines a (usually infinite) family of
 * {@link CoordinateReferenceSystem}s,
 * distinguished by different values for the
 * projection parameters.
 */
public abstract class Projection implements Cloneable, java.io.Serializable {

    /**
     * Pinned so that a {@code Projection} shipped inside a Spark closure does not fail with
     * {@link java.io.InvalidClassException} against an executor running a differently-compiled
     * copy of this class. Every {@code Projection} subclass now declares one too.
     *
     * <p>The value is <em>not</em> arbitrary: it is what {@code serialver} computed for this
     * class immediately before the field was added, so streams written by the previous build
     * stay readable. Declaring a UID freezes it, so from here on the compatibility rules of
     * the Java serialization specification apply — adding or removing a non-transient field
     * changes the serialised form even though the UID no longer says so.
     *
     * <p>Enforced by {@code SerialVersionUidTest}, which fails if any serialisable class in
     * {@code core/src/main} lacks one.
     */
    private static final long serialVersionUID = -4685935599437104452L;

    /**
     * The minimum latitude of the bounds of this projection
     */
    protected double minLatitude = -Math.PI/2;

    /**
     * The minimum longitude of the bounds of this projection. This is relative to the projection centre.
     */
    protected double minLongitude = -Math.PI;

    /**
     * The maximum latitude of the bounds of this projection
     */
    protected double maxLatitude = Math.PI/2;

    /**
     * The maximum longitude of the bounds of this projection. This is relative to the projection centre.
     */
    protected double maxLongitude = Math.PI;

    /**
     * The latitude of the centre of projection
     */
    protected double projectionLatitude = 0.0;

    /**
     * The longitude of the centre of projection, in radians
     */
    protected double projectionLongitude = 0.0;

    /**
     * Standard parallel 1 (for projections which use it)
     */
    protected double projectionLatitude1 = 0.0;

    /**
     * Standard parallel 2 (for projections which use it)
     */
    protected double projectionLatitude2 = 0.0;

    /**
     * The projection alpha value
     */
    protected double alpha = Double.NaN;

    /**
     * The projection lonc value
     */
    protected double lonc = Double.NaN;

    /**
     * The projection scale factor
     */
    protected double scaleFactor = 1.0;

    /**
     * The false Easting of this projection
     */
    protected double falseEasting = 0;

    /**
     * The false Northing of this projection
     */
    protected double falseNorthing = 0;

    /**
     * The latitude of true scale. Only used by specific projections.
     */
    protected double trueScaleLatitude = 0.0;

    /**
     * The equator radius
     */
    protected double a = 0;

    /**
     * The eccentricity
     */
    protected double e = 0;

    /**
     * The eccentricity squared
     */
    protected double es = 0;

    /**
     * 1-(eccentricity squared)
     */
    protected double one_es = 0;

    /**
     * 1/(1-(eccentricity squared))
     */
    protected double rone_es = 0;

    /**
     * The ellipsoid used by this projection
     */
    protected Ellipsoid ellipsoid;

    /**
     * True if this projection is using a sphere (es == 0)
     */
    protected boolean spherical;

    /**
     * True if this projection is geocentric
     */
    protected boolean geocentric;

    /**
     * PROJ's {@code P->over}, the {@code +over} flag: <em>do not</em> reduce the inverse's
     * longitude to &plusmn;&pi;.
     * <p>
     * Default false, which is PROJ's default and the behaviour every projection here had before
     * the field existed. See {@link #setOver(boolean)} for what reads it and what does not.
     */
    private boolean over = false;

    /**
     * The name of this projection
     */
    protected String name = null;

    /**
     * Conversion factor from metres to whatever units the projection uses.
     */
    protected double fromMetres = 1;

    /**
     * The total scale factor = Earth radius * units
     */
    protected double totalScale = 0;

    /**
     * PROJ's {@code P->ra}, generalised to Proj4J's {@link #totalScale}: {@code 1.0 / totalScale},
     * recomputed by {@link #initialize()}.
     * <p>
     * The inverse <b>multiplies</b> by this rather than dividing by {@link #totalScale}, because
     * {@code 9.8.1:src/inv.cpp:85-93} does — {@code coo.xyz.x *= P->ra;}, with
     * {@code P->ra = 1. / P->a} set at {@code ell_set.cpp:618}. Upstream's comment gives
     * {@code calcofi} as the motive (it "stomps on {@code a}"), and Proj4J reproduces that too,
     * because {@code CalCOFIProjection.initialize()} assigns {@code a = 1} <em>before</em> calling
     * {@code super.initialize()}, so this field is captured from the stomped value exactly as
     * {@code P->ra} is.
     * <p>
     * But the two are not interchangeable even without {@code calcofi}, and that is the reason this
     * field exists rather than a comment: {@code -20037508.342789244 / 6378137} is
     * {@code -pi - 1 ulp}, whose {@code atan2} is {@code +pi}, while
     * {@code -20037508.342789244 * (1.0 / 6378137)} is <em>exactly</em> {@code -pi}, whose
     * {@code atan2} is {@code -pi}. That one ulp was the whole of the {@code EPSG:3857} &rarr;
     * {@code EPSG:4055} sign error at the antimeridian.
     *
     * @since 1.5.0
     */
    private double totalScaleReciprocal = Double.POSITIVE_INFINITY;

    /**
     * falseEasting, adjusted to the appropriate units using fromMetres
     */
    private double totalFalseEasting = 0;

    /**
     * falseNorthing, adjusted to the appropriate units using fromMetres
     */
    private double totalFalseNorthing = 0;

    /**
     * units of this projection.  Default is metres, but may be degrees
     */
    protected Unit unit = null;

    /**
     * PrimeMeridian defining an offset from the Greenwich (the prime meridian used in WGS84)
     */
    private PrimeMeridian primeMeridian = PrimeMeridian.forName("greenwich");

    /**
     * The order of axes for the coordinate system. Default is easting,
     * northing, vertical (up)
     */
    private AxisOrder axes = AxisOrder.ENU;

    // Some useful constants
    protected final static double EPS10 = 1e-10;
    protected final static double RTD = 180.0/Math.PI;
    protected final static double DTR = Math.PI/180.0;

    /**
     * PROJ's {@code PJ_EPS_LAT} ({@code 9.8.1:src/proj_internal.h:99}): the largest latitudinal
     * overshoot {@code fwd_prepare} accepts, in <b>radians</b>.
     * <p>
     * {@code 1e-12} rad is about {@code 5.7e-11} degrees, or 0.006 &micro;m on the ground. It is
     * the width of the band inside which a latitude past the pole is treated as rounding noise
     * and <em>clamped</em> to exactly &plusmn;&pi;/2; anything beyond it is rejected. Latitude
     * 90.000001&deg; overshoots by {@code 1.745e-8} rad and is therefore rejected.
     * <p>
     * Do not approximate this in degree space and do not widen it: PROJ chose the value so that
     * legitimate rounding noise is absorbed and genuine bad input is not.
     *
     * @since 1.5.0
     */
    public final static double PJ_EPS_LAT = 1e-12;

    /**
     * {@code fwd_prepare}'s longitude bound ({@code 9.8.1:src/fwd.cpp:67}), in <b>radians</b>:
     * {@code |lambda| > 10} is rejected, everything inside is wrapped.
     * <p>
     * 10 radians is about &plusmn;573&deg;. <b>PROJ does not reject longitude outside
     * [&minus;180, 180]</b>, and neither does Proj4J: a caller legitimately passing 200&deg; or
     * &minus;190&deg; must keep working, so a tighter bound here would be stricter than PROJ.
     *
     * @since 1.5.0
     */
    public final static double MAX_LAM_RAD = 10.0;

    private final static double HALF_PI = Math.PI / 2.0;

    protected Projection() {
        setEllipsoid( Ellipsoid.SPHERE );
    }

    public Object clone() {
        try {
            Projection e = (Projection)super.clone();
            return e;
        }
        catch ( CloneNotSupportedException e ) {
            throw new InternalError();
        }
    }

    /**
     * Projects a geographic point (in degrees), producing a projected result
     * (in the units of the target coordinate system).
     *
     * @param src the input geographic coordinate (in degrees)
     * @param dst the projected coordinate (in coordinate system units)
     * @return the target coordinate
     */
    public ProjCoordinate project( ProjCoordinate src, ProjCoordinate dst ) {
        // The lam0 subtraction and the wrap used to happen HERE, ahead of the funnel, which put
        // them ahead of the domain check as well. Both now live in
        // projectRadians(double, double, ProjCoordinate), in fwd_prepare's order. See there.
        return projectRadians(src.x*DTR, src.y*DTR, dst);
    }

    /**
     * Projects a geographic point (in radians), producing a projected result
     * (in the units of the target coordinate system).
     *
     * @param src the input geographic coordinate (in radians)
     * @param dst the projected coordinate (in coordinate system units)
     * @return the target coordinate
     *
     */
    public ProjCoordinate projectRadians( ProjCoordinate src, ProjCoordinate dst ) {
        return projectRadians(src.x, src.y, dst);
    }

    /**
     * Transform a geographic point (in radians),
     * producing a projected result (in the units of the target coordinate system).
     * <p>
     * This is the private funnel <em>every</em> forward projection passes through, and so it is
     * where PROJ's host-level input contract and output check live, in {@code fwd_prepare}'s own
     * order:
     * <ol>
     * <li>{@link #checkForwardDomain} reproduces {@code fwd_prepare}
     *     ({@code 9.8.1:src/fwd.cpp:54-77}) <b>on the raw longitude and latitude</b>, and returns
     *     the clamped latitude;</li>
     * <li><em>then</em> {@code projectionLongitude} is subtracted and the result wrapped
     *     ({@code fwd.cpp:105-111});</li>
     * <li>the raw {@link #project(double, double, ProjCoordinate)} result is tested for
     *     finiteness <b>before</b> the affine post-multiply.</li>
     * </ol>
     *
     * <h2>Step 1 before step 2, which is not where they used to be</h2>
     *
     * <p>{@code project(ProjCoordinate, ProjCoordinate)} and
     * {@code projectRadians(ProjCoordinate, ProjCoordinate)} each used to do the subtraction and the
     * wrap themselves, <em>before</em> handing over to this funnel — so the domain check ran on the
     * <em>reduced</em> longitude, and two classes of bad input got through whenever
     * {@code lon_0 != 0}. Upstream has no such hole: {@code fwd.cpp:40} tests {@code HUGE_VAL} and
     * {@code fwd.cpp:57-70} tests both ranges <b>first</b>, and only then does {@code fwd.cpp:105}
     * reach {@code coo.lp.lam = (coo.lp.lam - P->from_greenwich) - P->lam0}. Both measured, on
     * {@code +proj=laea +lat_0=90 +lon_0=-150 +datum=WGS84}:
     *
     * <table>
     * <caption>domain checks against the reduced longitude</caption>
     * <tr><th>input</th><th>before</th><th>with {@code lon_0=0}</th></tr>
     * <tr><td>{@code (+Infinity, 0.5)}</td>
     *     <td>{@code (NaN, NaN)}, no exception — {@code adjlon(inf)} is {@code inf - inf}, i.e.
     *         {@code NaN} (faithfully to {@code 9.8.1:src/adjlon.cpp}), and the {@code NaN} route
     *         then <em>propagated</em> what should have been refused</td>
     *     <td>{@link ErrorCause#INVALID_COORDINATE}, correctly</td></tr>
     * <tr><td>{@code (20.0 rad, 0.5)}</td>
     *     <td>{@code (-3819350.5146746966, 5273204.385775552)} — a wholly plausible coordinate from
     *         a longitude of 1146&deg;, because after reduction and wrapping the value tested was
     *         inside {@link #MAX_LAM_RAD}</td>
     *     <td>{@link ErrorCause#INVALID_COORDINATE}, correctly</td></tr>
     * </table>
     *
     * <p>The same asymmetry made {@code +over} misbehave in the opposite direction: a raw longitude
     * of 9.9 rad is legal, but with {@code lon_0=-150} and no wrap to bring it back the reduced
     * value was 12.5 rad, and the check — being applied after the reduction — <em>rejected</em> a
     * coordinate upstream accepts. Checking the raw value fixes both directions at once, which is
     * why the check moved rather than being duplicated.
     *
     * <p>Step 3 is what makes it <em>arithmetically impossible</em> for a projection to answer an
     * internal {@code NaN} with the false easting/northing: for finite input,
     * {@code totalFalseEasting} is only ever added to a number already known to be finite.
     * Proj4J 1.4.3 applied the affine unconditionally, so {@code NaN * totalScale +
     * totalFalseEasting} produced {@code (x_0, y_0)} whenever the kernel produced {@code NaN} in
     * a projection whose {@code totalScale} was zero, and {@code NaN} otherwise — one of the two
     * shapes a caller cannot detect.
     *
     * <h2>{@code NaN} input is propagated, not rejected — and that is deliberate</h2>
     *
     * <p>A {@code NaN} the caller supplied is <em>propagated</em>, by an early return taken
     * <b>before</b> {@link #project(double, double, ProjCoordinate)}: the checks are skipped, the
     * kernel is never invoked, and the answer is {@code NaN} in both ordinates. That is
     * {@code 9.8.1:src/trans.cpp:352-354} exactly — {@code pj_coord_has_nans} short-circuits ahead
     * of {@code pj_fwd4d}, so no upstream kernel ever sees a {@code NaN} either. It matters that
     * the early return is here and not in the kernels: {@code laea_e_forward}'s polar arm ends
     * {@code else out.x = out.y = 0.;}, i.e. the <em>origin</em> for undefined input, and that
     * statement is unreachable upstream for precisely this reason. Doing the same at this funnel
     * makes the identical latent arm unreachable in all ~110 kernels here at once, rather than
     * needing 110 separate fixes. Three independent reasons for propagating rather than throwing,
     * and it took a conformance regression to establish that this is not a hole in the fail-closed
     * policy but a distinction the policy has to make:
     *
     * <ul>
     * <li><b>PROJ does it deliberately.</b> {@code fwd_prepare} compares against
     *     {@code HUGE_VAL} only, and every range comparison it makes is false for {@code NaN}, so
     *     upstream lets {@code NaN} flow through untouched. Its {@code adjlon} is
     *     {@code NaN}-transparent for the same reason, and several upstream convergence tests are
     *     written inverted — {@code !(|dtau| >= stol)}, {@code !(|tau| < TMAX)} — specifically so
     *     that {@code NaN} takes the exit branch. This is careful, not accidental.</li>
     * <li><b>The corpus asserts it.</b> The {@code gie} comparator's first metric branch is
     *     {@code isnan(got) && isnan(expected) -> d = 0}, i.e. a <em>pass</em>, and
     *     {@code roundtrip} defines all-{@code NaN}-in / all-{@code NaN}-out as residual
     *     {@code 0.0}. Rows exist that assert exactly that. Rejecting {@code NaN} input trades
     *     silent wrong answers for a conformance regression.</li>
     * <li><b>It is honest.</b> The caller asked about an undefined point and got an undefined
     *     point back. Nothing was invented and nothing was hidden — which is the actual content
     *     of the no-sentinels rule, not "never return {@code NaN}".</li>
     * </ul>
     *
     * <p>So the three cases are:
     * <table>
     * <caption>input to outcome</caption>
     * <tr><th>input</th><th>outcome</th><th>why</th></tr>
     * <tr><td>{@code NaN}</td><td>{@code NaN} result, no throw</td>
     *     <td>propagated; the caller supplied the undefinedness</td></tr>
     * <tr><td>&plusmn;{@code Infinity}, or finite outside the contract</td>
     *     <td>{@link ErrorCause#INVALID_COORDINATE}</td>
     *     <td>a well-formed question with an answer PROJ refuses to give</td></tr>
     * <tr><td>finite, in contract, non-finite <em>output</em></td>
     *     <td>{@link ErrorCause#NUMERICAL_FAILURE}</td>
     *     <td>the arithmetic failed; nothing in the input excuses it</td></tr>
     * </table>
     *
     * @param lam the geographic longitude, in radians, <b>as the caller supplied it</b>: the
     *            central meridian has not been subtracted yet and the value has not been wrapped
     * @param phi the geographic latitude, in radians
     * @param dst the projected coordinate (in coordinate system units)
     * @return the target coordinate
     * @throws ProjectionException with {@link ErrorCause#INVALID_COORDINATE} if the input is
     *         infinite or finite-but-outside PROJ's angular input contract, or with
     *         {@link ErrorCause#NUMERICAL_FAILURE} if finite input produced a non-finite result
     */
    private ProjCoordinate projectRadians(double lam, double phi, ProjCoordinate dst ) {
        double x = lam;
        double y = phi;
        if (Double.isNaN(x) || Double.isNaN(y)) {
            // 9.8.1:src/trans.cpp:352-354 -- `if (pj_coord_has_nans(coord)) coord.v[0] = ... =
            // quiet_NaN();` -- is an early return taken BEFORE pj_fwd4d, so upstream never invokes
            // the operation at all on a coordinate carrying a NaN. Reproducing it here rather than
            // in each kernel is what makes the identical latent bug in all ~110 of them
            // unreachable: laea_e_forward's own polar arm ends `else out.x = out.y = 0.;`, and that
            // zero is dead code upstream for exactly this reason. Confirmed against the installed
            // 9.8.1 gie, which reports `GOT nan nan` and stays `nan nan` when +x_0=1000000 is
            // added, proving the zero never reaches fwd_finalize.
            dst.x = Double.NaN;
            dst.y = Double.NaN;
            return dst;
        }
        // fwd.cpp:40-70, on the RAW coordinate. Both range tests are upstream's, in upstream's
        // order, and both are taken before anything is subtracted from the longitude.
        y = checkForwardDomain(x, y);
        // fwd.cpp:105-111 -- and only now. `+Infinity` and |lam| > 10 rad can no longer reach this
        // arithmetic, so adjlon can no longer launder an infinity into a NaN behind the guard's
        // back.
        if ( projectionLongitude != 0 ) {
            x -= projectionLongitude;
            // fwd_prepare (9.8.1:src/fwd.cpp:109-111) subtracts lam0 unconditionally and
            // wraps only when `+over` is off. Splitting the two apart matters: folding the
            // wrap into the same guard would have skipped the SUBTRACTION too under +over,
            // which is a change of central meridian rather than a change of wrapping.
            if ( !over )
                x = ProjectionMath.normalizeLongitude( x );
        }
        project(x, y, dst);
        if (!dst.hasValidXandYOrdinates()) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
                    "forward projection of (" + x + ", " + y + ") rad returned a non-finite "
                            + "coordinate (" + dst.x + ", " + dst.y + "); refusing to apply "
                            + "totalScale/false easting to it");
        }
        if (unit != null && unit.equals(Units.DEGREES)) {
            // convert radians to DD
            dst.x *= RTD;
            dst.y *= RTD;
        }
        else {
            // assume result is in metres
            dst.x = totalScale * dst.x + totalFalseEasting;
            dst.y = totalScale * dst.y + totalFalseNorthing;
        }
        return dst;
    }

    /**
     * PROJ's {@code fwd_prepare} angular input contract, {@code 9.8.1:src/fwd.cpp:54-77}, in the
     * order upstream applies it: reject non-finite, reject a latitude past the pole by more than
     * {@link #PJ_EPS_LAT}, reject {@code |lambda| >} {@link #MAX_LAM_RAD}, then <em>clamp</em>
     * the latitude to exactly &plusmn;&pi;/2.
     * <p>
     * Three things about this are deliberate and easy to get wrong:
     * <ul>
     * <li><b>The latitude bound is on {@code |phi| - pi/2} in radians</b>, not on degrees, and
     *     the comparison is strictly greater — so 90&deg; exactly is accepted, and so is
     *     anything within {@code 1e-12} rad beyond it, which is then clamped. Doing this in
     *     degree space misclassifies points a micro-degree from the pole.</li>
     * <li><b>There is no [&minus;180, 180] longitude rejection.</b> The only longitude bound is
     *     {@code |lambda| > 10} radians; see {@link #MAX_LAM_RAD}.</li>
     * <li><b>{@code NaN} must not reach this method.</b> Callers test for it first and propagate
     *     it; see {@link #projectRadians(double, double, ProjCoordinate)}. Only
     *     &plusmn;{@code Infinity} is rejected here, matching upstream: {@code +Infinity} trips
     *     {@code fwd_prepare}'s {@code HUGE_VAL} test directly and {@code -Infinity} trips the
     *     latitude range test, so both are errors in PROJ and both are errors here.</li>
     * </ul>
     *
     * @param lam longitude relative to the central meridian, radians; must not be {@code NaN}
     * @param phi latitude, radians; must not be {@code NaN}
     * @return {@code phi}, clamped into {@code [-pi/2, pi/2]}
     * @throws ProjectionException with {@link ErrorCause#INVALID_COORDINATE} for every input
     *         PROJ answers with {@code PROJ_ERR_COORD_TRANSFM_INVALID_COORD} (2049)
     * @since 1.5.0
     */
    protected double checkForwardDomain(double lam, double phi) {
        if (Double.isInfinite(lam) || Double.isInfinite(phi)) {
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE, this,
                    "infinite geographic input (" + lam + ", " + phi + ") rad");
        }
        double overshoot = Math.abs(phi) - HALF_PI;
        if (overshoot > PJ_EPS_LAT) {
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE, this,
                    "invalid latitude " + (phi * RTD) + " deg: |phi| - pi/2 = " + overshoot
                            + " rad exceeds PJ_EPS_LAT = " + PJ_EPS_LAT);
        }
        if (lam > MAX_LAM_RAD || lam < -MAX_LAM_RAD) {
            throw new ProjectionException(ErrorCause.INVALID_COORDINATE, this,
                    "invalid longitude " + lam + " rad (" + (lam * RTD) + " deg): outside +/-"
                            + MAX_LAM_RAD + " rad");
        }
        // Inside the slop band, clamp to exactly the pole. fwd.cpp:72-77.
        if (phi > HALF_PI) {
            return HALF_PI;
        }
        if (phi < -HALF_PI) {
            return -HALF_PI;
        }
        return phi;
    }


    /**
     * Computes the projection of a given point
     * (i.e. from geographics to projection space).
     * This should be overridden for all projections.
     *
     * @param x the geographic x ordinate (in radians)
     * @param y the geographic y ordinatee (in radians)
     * @param dst the projected coordinate (in coordinate system units)
     * @return the target coordinate
     */
    protected ProjCoordinate project(double x, double y, ProjCoordinate dst) {
        dst.x = x;
        dst.y = y;
        return dst;
    }

    /**
     * Inverse-projects a point (in the units defined by the coordinate system),
     * producing a geographic result (in degrees)
     *
     * @param src the input projected coordinate (in coordinate system units)
     * @param dst the inverse-projected geographic coordinate (in degrees)
     * @return the target coordinate
     */
    public ProjCoordinate inverseProject(ProjCoordinate src, ProjCoordinate dst) {
        inverseProjectRadians(src, dst);
        dst.x *= RTD;
        dst.y *= RTD;
        return dst;
    }

    /**
     * Inverse-transforms a point (in the units defined by the coordinate system),
     * producing a geographic result (in radians)
     *
     * @param src the input projected coordinate (in coordinate system units)
     * @param dst the inverse-projected geographic coordinate (in radians)
     * @return the target coordinate
     *
     */
    public ProjCoordinate inverseProjectRadians(ProjCoordinate src, ProjCoordinate dst) {
        if (Double.isNaN(src.x) || Double.isNaN(src.y)) {
            // The same early return as the forward funnel, and the same citation:
            // 9.8.1:src/trans.cpp:352-354 short-circuits ahead of pj_inv4d as well as pj_fwd4d, so
            // no inverse kernel is ever invoked on a coordinate carrying a NaN. See
            // projectRadians(double, double, ProjCoordinate) for the measurements and for why the
            // answer is a NaN RESULT rather than an exception.
            //
            // One consequence is deliberate and is upstream's: a projection with no inverse at all
            // answers NaN here instead of ErrorCause#NO_INVERSE_AVAILABLE, because the capability
            // gate lives in projectInverse and projectInverse is no longer reached. That is
            // faithful -- trans.cpp does not consult P->inv either -- and it costs nothing, since
            // hasInverse() is the API for asking about capability and every FINITE coordinate still
            // gets NO_INVERSE_AVAILABLE. Measured: 21 of the 131 constructible projections take
            // that path. Note also that hasInverse() is NOT a sound capability predicate on its own
            // -- krovak and nzmg both return false while overriding projectInverse -- so hoisting
            // the gate out of projectInverse to keep the throw would have broken their inverses.
            dst.x = Double.NaN;
            dst.y = Double.NaN;
            return dst;
        }
        double x;
        double y;
        if (unit != null && unit.equals(Units.DEGREES)) {
            // convert DD to radians
            x = src.x * DTR;
            y = src.y * DTR;
            // The second entry point for the angular input contract, and the one that matters
            // most in practice. BasicCoordinateTransform calls
            // srcCRS.getProjection().inverseProjectRadians(tgt, tgt) even for a *geographic*
            // source, where LongLatProjection's "inverse" is just this multiply by DTR. So this
            // is where EPSG:4326 input at latitude 90.000001 deg actually enters the library --
            // it never reaches projectRadians at all. Guarding only the forward funnel leaves
            // the commonest source CRS in the world unchecked.
            y = checkForwardDomain(x, y);
        }
        else {
            // inv_prepare (9.8.1:src/inv.cpp) tests HUGE_VAL and nothing else: its input is
            // planar, so there is no latitude or longitude range to check.
            if (Double.isInfinite(src.x) || Double.isInfinite(src.y)) {
                throw new ProjectionException(ErrorCause.INVALID_COORDINATE, this,
                        "infinite projected input (" + src.x + ", " + src.y + ")");
            }
            // MULTIPLY by the reciprocal, do not divide -- 9.8.1:src/inv.cpp:85-93 is
            // `coo.xyz.x *= P->ra;` and it carries a comment explaining the choice ("Multiplying by
            // ra, rather than dividing by a because the CalCOFI projection stomps on a and hence
            // (apparently) depends on this to roundtrip correctly"). It is not only about calcofi:
            // the two are not the same number.
            //
            //   -20037508.342789244 / 6378137            = -pi - 1 ulp   -> atan2 gives  +pi
            //   -20037508.342789244 * (1.0 / 6378137)    = exactly -pi   -> atan2 gives  -pi
            //
            // so EPSG:3857 -> EPSG:4055 at the western antimeridian returned +180 where PROJ
            // returns -180. Master survived it only because the inverse CLAMPED longitude to +/-pi
            // and the clamp happened to force -pi; removing the clamp was correct (PROJ wraps, it
            // does not clamp) and it exposed the division underneath.
            x = (src.x - totalFalseEasting) * totalScaleReciprocal;
            y = (src.y - totalFalseNorthing) * totalScaleReciprocal;
        }

        projectInverse(x, y, dst);

        // Checked BEFORE the clamp and the wrap, which is the whole point. A NaN from
        // projectInverse passes both clamp comparisons (NaN fails every < and >) and then
        // reached normalizeLongitude -- which threw, but only when projectionLongitude != 0. So
        // the identical defect threw for +lon_0=15 and returned garbage for +lon_0=0. That one
        // asymmetry accounts for most "sometimes it throws, sometimes it returns a coordinate"
        // reports against 1.4.3.
        if (!dst.hasValidXandYOrdinates()) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
                    "inverse projection of (" + x + ", " + y + ") returned a non-finite "
                            + "coordinate (" + dst.x + ", " + dst.y + ")");
        }

        // PROJ's inv_finalize (9.8.1:src/inv.cpp:113-117), which is two statements:
        //
        //     coo.lp.lam = coo.lp.lam + P->from_greenwich + P->lam0;
        //     if (0 == P->over)
        //         coo.lpz.lam = adjlon(coo.lpz.lam);
        //
        // Add the central meridian, then WRAP unless `+over` says not to.
        // `from_greenwich` is applied separately, by PrimeMeridian in BasicCoordinateTransform.
        //
        // This used to CLAMP to +/-pi first --
        //
        //     if (dst.x < -Math.PI) dst.x = -Math.PI;
        //     else if (dst.x > Math.PI) dst.x = Math.PI;
        //     if (projectionLongitude != 0)
        //         dst.x = ProjectionMath.normalizeLongitude(dst.x + projectionLongitude);
        //
        // -- and a clamp is a silent substitution, not a reduction: it discards the revolution
        // count instead of removing it, so any inverse kernel that legitimately accumulates
        // longitude past the antimeridian had its answer replaced by the antimeridian itself.
        // Measured on +proj=lsat +lsat=3 +path=120, whose kernel accumulates along-track rotation
        // in `lamt - p22 * lamdp`: at (130, 45) it returns 533.2 deg, the clamp turned that into
        // 180 deg, and the final answer into 136.758 deg -- 6.76 deg wrong, with no error raised.
        // adjlon returns the correct value because 533.2 - 360 = 173.2 is the same meridian.
        //
        // The second half of the defect was the `projectionLongitude != 0` guard: with +lon_0=0
        // no reduction happened at all, so the identical bad input threw for +lon_0=15 and
        // returned a plausible wrong coordinate for +lon_0=0.
        //
        // A NaN the caller supplied never reaches this line at all: the trans.cpp early return at
        // the top of this method already answered it. (adjlon would have been NaN-transparent
        // anyway -- 9.8.1:src/adjlon.cpp, where |NaN| < pi + 1e-12 is false so NaN falls through
        // the arithmetic -- but relying on that left the KERNEL free to fabricate a finite value
        // from NaN, which is the defect the early return closes.) A NaN arising from FINITE input
        // never reaches this line either; the postcondition above rejects it.
        //
        // Note adjlon keeps PROJ's own limitation that a single floor loses the low bits of a
        // huge argument, so adjlon(1e18) is 124.858 rather than something inside +/-pi. That is
        // upstream's number and is deliberately reproduced.
        //
        // `over` is honoured, so PROJ's else-branch is reachable, and it is now reachable from a
        // proj-string as well: Proj4Parser dispatches +over to setOver. +proj=calcofi still sets
        // the flag from its own initialize(), because calcofi.cpp:141 hard-codes P->over = 1.
        dst.x = dst.x + projectionLongitude;
        if (!over) {
            dst.x = ProjectionMath.adjlon(dst.x);
        }

        return dst;
    }

    /**
     * Computes the inverse projection of a given point
     * (i.e. from projection space to geographics).
     * This should be overridden for all projections.
     *
     * <h4>This is no longer an unconditional identity</h4>
     *
     * <p>It used to be {@code dst.x = x; dst.y = y;} for every subclass that did not override it,
     * and nothing anywhere consulted {@link #hasInverse()} — which is declared 66 times across
     * 102 classes and, before 1.5.0, was <b>read nowhere</b> in {@code core/src/main}. The
     * consequence was that 33 forward-only projections used as a <em>source</em> CRS returned the
     * projected metres back as if they were lon/lat radians: {@code Airy}, {@code Aitoff},
     * {@code August}, {@code Boggs}, {@code Denoyer}, {@code Euler}, {@code Foucaut},
     * {@code Ginsburg8}, {@code Hammer}, {@code KavraiskyV}, {@code Lagrange},
     * {@code LambertEqualAreaConic}, {@code Larrivee}, {@code Laskowski},
     * {@code McBrydeThomasFlatPolarSine1}, {@code Murdoch1/2/3}, {@code Nicolosi},
     * {@code PerspectiveConic}, {@code Perspective}, {@code PutninsP5P},
     * {@code QuarticAuthalic}, {@code RectangularPolyconic}, {@code Tissot},
     * {@code TranverseCentralCylindrical}, {@code Vitkovsky}, {@code Wagner1/4/5/7},
     * {@code Werenskiold}, {@code WinkelTripel}.
     *
     * <p>So the identity is now <b>opt-in</b>: it is returned only by a projection that declares
     * {@link #hasInverse()}, or by a geographic pseudo-projection ({@link #isGeographic()}).
     * Everything else raises {@link ErrorCause#NO_INVERSE_AVAILABLE}.
     *
     * <p>The two predicates are both needed, and neither is redundant:
     * <ul>
     * <li>{@code LongLatProjection.hasInverse()} is {@code false} — it inherits the base — yet
     *     its inverse is real and is the DTR multiply in
     *     {@link #inverseProjectRadians(ProjCoordinate, ProjCoordinate)}. Gating on
     *     {@code hasInverse()} alone would break every geographic source CRS, EPSG:4326
     *     included.</li>
     * <li>{@code PlateCarreeProjection} ({@code +proj=eqc}) and {@code LinearProjection} declare
     *     {@code hasInverse()} and their real inverse <em>is</em> this identity, because their
     *     forward is the base identity too.</li>
     * </ul>
     *
     * <p>There is no longer any case knowingly left silent. {@code LandsatProjection} used to be
     * one: it declared {@code hasInverse()} while its only inverse was
     * {@code projectInverse(double, double, Point2D.Double)}, a stale AWT signature that
     * overrode nothing — and the body was additionally sealed inside a block comment — so
     * {@code +proj=lsat} landed on this identity. {@code hasInverse()} was <em>right</em> and the
     * implementation was missing: upstream's {@code som_setup}
     * ({@code 9.8.1:src/projections/som.cpp}) assigns {@code P->inv} unconditionally for
     * {@code som}, {@code misrsom} and {@code lsat} alike. It now has a real
     * {@code projectInverse(double, double, ProjCoordinate)}.
     *
     * @param x the projected x ordinate (in coordinate system units)
     * @param y the projected y ordinate (in coordinate system units)
     * @param dst the inverse-projected geographic coordinate  (in radians)
     * @return the target coordinate
     * @throws ProjectionException with {@link ErrorCause#NO_INVERSE_AVAILABLE} if this
     *         projection has no inverse
     */
    protected ProjCoordinate projectInverse(double x, double y, ProjCoordinate dst) {
        if (!hasInverse() && !isGeographic()) {
            throw new ProjectionException(ErrorCause.NO_INVERSE_AVAILABLE, this,
                    "no inverse projection is available; hasInverse() is false and no "
                            + "projectInverse override exists, so the base class would "
                            + "otherwise return the projected input as lon/lat radians");
        }
        dst.x = x;
        dst.y = y;
        return dst;
    }


    /**
     * Tests whether this projection is conformal.
     * A conformal projection preserves local angles.
     *
     * @return true if this projection is conformal
     */
    public boolean isConformal() {
        return false;
    }

    /**
     * Tests whether this projection is equal-area
     * An equal-area projection preserves relative sizes
     * of projected areas.
     *
     * @return true if this projection is equal-area
     */
    public boolean isEqualArea() {
        return false;
    }

    /**
     * Tests whether this projection has an inverse.
     * If this method returns <code>true</code>
     * then the {@link #inverseProject(ProjCoordinate, ProjCoordinate)}
     * and {@link #inverseProjectRadians(ProjCoordinate, ProjCoordinate)}
     * methods will return meaningful results.
     *
     * @return true if this projection has an inverse
     */
    public boolean hasInverse() {
        return false;
    }

    /**
     * Tests whether under this projection lines of
     * latitude and longitude form a rectangular grid
     */
    public boolean isRectilinear() {
        return false;
    }

    /**
     * Returns true if latitude lines are parallel for this projection
     */
    public boolean parallelsAreParallel() {
        return isRectilinear();
    }

    /**
     * Returns true if the given lat/long point is visible in this projection
     */
    public boolean inside(double x, double y) {
        x = normalizeLongitude( (float)(x*DTR-projectionLongitude) );
        return minLongitude <= x && x <= maxLongitude && minLatitude <= y && y <= maxLatitude;
    }

    /**
     * Set the name of this projection.
     */
    public void setName( String name ) {
        this.name = name;
    }

    public String getName() {
        if ( name != null )
            return name;
        return toString();
    }

    /**
     * Get a string which describes this projection in PROJ.4 format.
     * <p>
     * WARNING: currently this does not output all required parameters in some cases.
     * E.g. for Albers the standard latitudes are missing.
     */
    public String getPROJ4Description() {
        AngleFormat format = new AngleFormat( AngleFormat.ddmmssPattern, false );
        StringBuffer sb = new StringBuffer();
        sb.append(
                  "+proj="+getName()+
                  " +a="+a
                  );
        if ( es != 0 )
            sb.append( " +es="+es );
        sb.append( " +lon_0=" );
        format.format( projectionLongitude, sb, null );
        sb.append( " +lat_0=" );
        format.format( projectionLatitude, sb, null );
        if ( falseEasting != 1 )
            sb.append( " +x_0="+falseEasting );
        if ( falseNorthing != 1 )
            sb.append( " +y_0="+falseNorthing );
        if ( scaleFactor != 1 )
            sb.append( " +k="+scaleFactor );
        if ( fromMetres != 1 )
            sb.append( " +fr_meters="+fromMetres );
        return sb.toString();
    }

    public String toString() {
        return "None";
    }

    public void setAxisOrder(String axes) {
        this.axes = AxisOrder.fromString(axes);
    }

    public AxisOrder getAxisOrder() {
        return this.axes;
    }

    public void setPrimeMeridian(String primeMeridian) {
        this.primeMeridian = PrimeMeridian.forName(primeMeridian);
    }

    public PrimeMeridian getPrimeMeridian() {
        return this.primeMeridian;
    }

    /**
     * Set the minimum latitude. This is only used for Shape clipping and doesn't affect projection.
     */
    public void setMinLatitude( double minLatitude ) {
        this.minLatitude = minLatitude;
    }

    public double getMinLatitude() {
        return minLatitude;
    }

    /**
     * Set the maximum latitude. This is only used for Shape clipping and doesn't affect projection.
     */
    public void setMaxLatitude( double maxLatitude ) {
        this.maxLatitude = maxLatitude;
    }

    public double getMaxLatitude() {
        return maxLatitude;
    }

    public double getMaxLatitudeDegrees() {
        return maxLatitude*RTD;
    }

    public double getMinLatitudeDegrees() {
        return minLatitude*RTD;
    }

    public void setMinLongitude( double minLongitude ) {
        this.minLongitude = minLongitude;
    }

    public double getMinLongitude() {
        return minLongitude;
    }

    public void setMinLongitudeDegrees( double minLongitude ) {
        this.minLongitude = DTR*minLongitude;
    }

    public double getMinLongitudeDegrees() {
        return minLongitude*RTD;
    }

    public void setMaxLongitude( double maxLongitude ) {
        this.maxLongitude = maxLongitude;
    }

    public double getMaxLongitude() {
        return maxLongitude;
    }

    public void setMaxLongitudeDegrees( double maxLongitude ) {
        this.maxLongitude = DTR*maxLongitude;
    }

    public double getMaxLongitudeDegrees() {
        return maxLongitude*RTD;
    }

    /**
     * Set the projection latitude in radians.
     */
    public void setProjectionLatitude( double projectionLatitude ) {
        this.projectionLatitude = projectionLatitude;
    }

    public double getProjectionLatitude() {
        return projectionLatitude;
    }

    /**
     * Set the projection latitude in degrees.
     */
    public void setProjectionLatitudeDegrees( double projectionLatitude ) {
        this.projectionLatitude = DTR*projectionLatitude;
    }

    public double getProjectionLatitudeDegrees() {
        return projectionLatitude*RTD;
    }

    /**
     * Set the projection longitude in radians.
     */
    public void setProjectionLongitude( double projectionLongitude ) {
        this.projectionLongitude = normalizeLongitudeRadians( projectionLongitude );
    }

    public double getProjectionLongitude() {
        return projectionLongitude;
    }

    /**
     * Set the projection longitude in degrees.
     */
    public void setProjectionLongitudeDegrees( double projectionLongitude ) {
        this.projectionLongitude = DTR*projectionLongitude;
    }

    public double getProjectionLongitudeDegrees() {
        return projectionLongitude*RTD;
    }

    /**
     * Set the latitude of true scale in radians. This is only used by certain projections.
     */
    public void setTrueScaleLatitude( double trueScaleLatitude ) {
        this.trueScaleLatitude = trueScaleLatitude;
    }

    public double getTrueScaleLatitude() {
        return trueScaleLatitude;
    }

    /**
     * Set the latitude of true scale in degrees. This is only used by certain projections.
     */
    public void setTrueScaleLatitudeDegrees( double trueScaleLatitude ) {
        this.trueScaleLatitude = DTR*trueScaleLatitude;
    }

    public double getTrueScaleLatitudeDegrees() {
        return trueScaleLatitude*RTD;
    }

    /**
     * Set the projection latitude in radians.
     */
    public void setProjectionLatitude1( double projectionLatitude1 ) {
        this.projectionLatitude1 = projectionLatitude1;
    }

    public double getProjectionLatitude1() {
        return projectionLatitude1;
    }

    /**
     * Set the projection latitude in degrees.
     */
    public void setProjectionLatitude1Degrees( double projectionLatitude1 ) {
        this.projectionLatitude1 = DTR*projectionLatitude1;
    }

    public double getProjectionLatitude1Degrees() {
        return projectionLatitude1*RTD;
    }

    /**
     * Set the projection latitude in radians.
     */
    public void setProjectionLatitude2( double projectionLatitude2 ) {
        this.projectionLatitude2 = projectionLatitude2;
    }

    public double getProjectionLatitude2() {
        return projectionLatitude2;
    }

    /**
     * Set the projection latitude in degrees.
     */
    public void setProjectionLatitude2Degrees( double projectionLatitude2 ) {
        this.projectionLatitude2 = DTR*projectionLatitude2;
    }

    public double getProjectionLatitude2Degrees() {
        return projectionLatitude2*RTD;
    }

    /**
     * Sets the alpha value.
     */
    public void setAlpha( double alpha ) {
        this.alpha = alpha;
    }
    
    /**
     * Sets the alpha value.
     */
    public void setAlphaDegrees( double alpha ) {
        this.alpha = DTR * alpha;
    }

    /**
     * Gets the alpha value, in radians.
     *
     * @return the alpha value
     */
    public double getAlpha()
    {
        return alpha;
    }
    
    /**
     * Sets the lonc value.
     */
    public void setLonC( double lonc ) {
        this.lonc = lonc;
    }
    
    /**
     * Sets the lonc value.
     */
    public void setLonCDegrees( double lonc ) {
    	this.lonc = DTR * lonc;
    }

    /**
     * Gets the lonc value, in radians.
     *
     * @return the lonc value
     */
    public double getLonC()
    {
        return lonc;
    }

    /**
     * Set the false Northing in projected units.
     */
    public void setFalseNorthing( double falseNorthing ) {
        this.falseNorthing = falseNorthing;
    }

    public double getFalseNorthing() {
        return falseNorthing;
    }

    /**
     * Set the false Easting in projected units.
     */
    public void setFalseEasting( double falseEasting ) {
        this.falseEasting = falseEasting;
    }

    public double getFalseEasting() {
        return falseEasting;
    }

    /**
     * Declares that the projection is in the southern hemisphere, the {@code +south} of
     * {@code 9.8.1:src/projections/utm.cpp} and {@code tmerc.cpp}.
     * <p>
     * The base class refuses, because it has no {@code +south} to set. Only
     * {@code TransverseMercatorProjection} and {@code ExtendedTransverseMercatorProjection}
     * override this.
     * <p>
     * <b>Behaviour change.</b> This used to throw a bare {@link java.util.NoSuchElementException NoSuchElementException} with
     * <em>no message</em>. That is unchecked and is <b>not</b> a {@link Proj4jException}, so
     * {@code +south} on a projection that does not read it escaped every
     * {@code catch (Proj4jException)} in the library and in every caller, and arrived with
     * nothing a caller could log. Fifty golden-master rows reach this line
     * ({@code SYN/mod/}&#123;{@code aea},{@code lcc},{@code longlat},{@code merc}&#125;{@code /south}
     * and the {@code /h} family below); the differential gate could not see them because the
     * defect predates its 1.4.3 baseline and the row is identical on both sides.
     * <p>
     * <b>Why this refuses rather than ignoring.</b> PROJ has no parameter allow-list — a key
     * no operator reads is retained and silently ignored ({@code 9.8.1:src/init.cpp} performs
     * no such validation, and {@code paralist::used} in {@code src/param.cpp} exists only for
     * {@code +init}/pipeline override resolution) — so refusing here is stricter than PROJ.
     * That is deliberate, and the reason is that the base class cannot distinguish the two
     * cases that reach it:
     * <ul>
     * <li>{@code +south} on {@code +proj=merc}, where the parameter is genuinely inapplicable
     *     and PROJ's ignoring it is correct; and
     * <li>{@code +h} on {@code +proj=nsper} (see {@link #setHeightOfOrbit(double)}), where PROJ
     *     <em>does</em> read the parameter and Proj4J has simply not wired it up. Ignoring that
     *     one yields a silently wrong coordinate computed from a default height.
     * </ul>
     * Only the parser knows which case it is holding. If {@code +south} and {@code +h} are ever
     * to be retained-and-ignored in {@code Proj4Parser.ParseMode#PROJ_COMPATIBLE} and refused
     * only in {@code STRICT}, the discrimination belongs in {@code Proj4Parser} against a table
     * of which projections legitimately do not read them; it cannot be made here. Until then
     * this refuses in both modes, which is the fail-closed direction.
     *
     * @param isSouth whether the projection is in the southern hemisphere
     * @throws UnsupportedParameterException always, on the base class
     */
    public void setSouthernHemisphere(boolean isSouth) {
        throw new UnsupportedParameterException(ErrorCause.PROJECTION_NOT_IMPLEMENTED,
                "+south is not supported by projection " + getName() + " (" + getClass().getName()
                        + "): it does not override setSouthernHemisphere. PROJ retains and "
                        + "ignores a parameter no operator reads, so this definition is one "
                        + "PROJ accepts; Proj4J refuses it because it cannot tell an "
                        + "inapplicable +south from an unimplemented one.");
    }

    /**
     * The {@code +south} flag. The base class has none; see
     * {@link #setSouthernHemisphere(boolean)} for why this refuses rather than answering
     * {@code false}, and for the {@link java.util.NoSuchElementException NoSuchElementException} it used to throw.
     *
     * @return never returns normally on the base class
     * @throws UnsupportedParameterException always, on the base class
     */
    public boolean getSouthernHemisphere() {
        throw new UnsupportedParameterException(ErrorCause.PROJECTION_NOT_IMPLEMENTED,
                "+south is not supported by projection " + getName() + " (" + getClass().getName()
                        + "): it does not override getSouthernHemisphere, so it has no "
                        + "southern-hemisphere flag to report. Answering false would be a "
                        + "fabricated value.");
    }

    /**
     * Set the projection scale factor. This is set to 1 by default.
     * This value is called "k0" in PROJ.4.
     */
    public void setScaleFactor( double scaleFactor ) {
        this.scaleFactor = scaleFactor;
    }

    /**
     * Gets the projection scale factor.
     * This value is called "k0" in PROJ.4.
     *
     * @return
     */
    public double getScaleFactor() {
        return scaleFactor;
    }

    public double getEquatorRadius() {
        return a;
    }

    /**
     * Set the conversion factor from metres to projected units. This is set to 1 by default.
     */
    public void setFromMetres( double fromMetres ) {
        this.fromMetres = fromMetres;
    }

    public double getFromMetres() {
        return fromMetres;
    }

    /**
     * PROJ's {@code +over}: suppress the &plusmn;&pi; reduction of the inverse's longitude.
     * <p>
     * {@code inv_finalize} ({@code 9.8.1:src/inv.cpp:113-117}) is
     * {@code lam += lam0; if (0 == P->over) lam = adjlon(lam);}, so {@code +over} is the only
     * escape from the wrap and it lets an inverse return a longitude outside
     * &plusmn;180&deg; &mdash; which is the point: on a map drawn past the antimeridian, 200&deg;E
     * and 160&deg;W are different places on the page.
     *
     * <h4>What this does NOT yet cover</h4>
     *
     * <p>Two deliberate gaps, both outside this class:
     * <ul>
     * <li><b>The forward still under-wraps.</b> {@code fwd_prepare}
     *     ({@code 9.8.1:src/fwd.cpp:82-83, :109-111}) calls {@code adjlon} <em>twice</em> when
     *     {@code over} is off — once on the raw longitude and once after subtracting
     *     {@code lam0} — whereas {@link #project(ProjCoordinate, ProjCoordinate)} normalises
     *     once and only when {@code lon_0 != 0}. Both of this class's forward funnels now skip
     *     that normalisation under {@code +over}, so the flag is honoured in the direction it
     *     is asked for; making the wrap unconditional in the {@code !over} case is a
     *     corpus-wide change and is tracked separately. It is what the single
     *     {@code +proj=vandg} row at {@code accept 180.1 50} — the block <em>without</em>
     *     {@code +over} — needs.</li>
     * </ul>
     *
     * <h4>Reachable from a proj-string since 1.5.0</h4>
     *
     * <p>{@code Proj4Parser} dispatches {@code +over} here, read with {@code pj_param}'s
     * {@code b} sigil ({@code init.cpp:601}) so that {@code +over=f} is explicitly off. It used
     * to be reachable only from {@code +proj=calcofi}, which sets it from its own
     * {@code initialize()} because {@code calcofi.cpp:141} hard-codes {@code P->over = 1};
     * {@code calcofi} also forces {@code lam0 = 0}, which is why widening the forward guard
     * above cannot move it.
     *
     * @param over true to keep the inverse's longitude unwrapped
     */
    public void setOver(boolean over) {
        this.over = over;
    }

    /**
     * The {@code +over} flag in force.
     *
     * @return true when the inverse's longitude is left unwrapped
     * @see #setOver(boolean)
     */
    public boolean isOver() {
        return over;
    }

    public void setEllipsoid( Ellipsoid ellipsoid ) {
        this.ellipsoid = ellipsoid;
        a = ellipsoid.equatorRadius;
        e = ellipsoid.eccentricity;
        es = ellipsoid.eccentricity2;
    }

    public Ellipsoid getEllipsoid() {
        return ellipsoid;
    }

    /**
     * Declares a sphere of the given radius, replacing whatever ellipsoid was in force.
     * <p>
     * This is the {@code +R=} semantic of {@code 9.8.1:src/ell_set.cpp:92-100}: a radius
     * overrules every shape parameter, so {@code es} and {@code e} become exactly zero and
     * the ellipsoid is replaced by a true sphere. Callers must still invoke
     * {@link #initialize()} afterwards for {@code spherical}, {@code one_es},
     * {@code rone_es} and {@code totalScale} to pick up the change.
     * <p>
     * <b>Behaviour change.</b> Before this fix the method assigned the semi-major axis
     * alone, leaving {@code e}, {@code es} and {@code ellipsoid} stale from the previous
     * ellipsoid, so {@code spherical} stayed {@code false} and the <em>ellipsoidal</em>
     * formula ran on a declared sphere — northing wrong by about 1,495 m at 2 degrees and
     * 35,000 m at 55 degrees. The {@code +R=} parse path no longer goes through here (see
     * {@code DatumParameters.setR}), but the method is public API and was wrong on its own
     * terms.
     *
     * @param radius the radius of the sphere, in metres
     */
    public void setRadius(double radius) {
        this.ellipsoid = new Ellipsoid("sphere", radius, 0.0,
                "Sphere of radius " + radius);
        a = radius;
        e = 0.0;
        es = 0.0;
    }

    /**
     * Returns the ESPG code for this projection, or 0 if unknown.
     */
    public int getEPSGCode() {
        return 0;
    }

    public void setUnits(Unit unit)
    {
        this.unit = unit;
    }

    public Unit getUnits() {
        return this.unit != null ? this.unit : Units.METRES;
    }

    /**
     * Get height of orbit - Geostationary satellite projection.
     * <p>
     * The base class has no orbit; see {@link #setHeightOfOrbit(double)} for why this refuses
     * rather than answering a default, and for the {@link java.util.NoSuchElementException NoSuchElementException} it used to
     * throw.
     *
     * @return never returns normally on the base class
     * @throws UnsupportedParameterException always, on the base class
     */
    public double getHeightOfOrbit(){
        throw new UnsupportedParameterException(ErrorCause.PROJECTION_NOT_IMPLEMENTED,
                "+h is not supported by projection " + getName() + " (" + getClass().getName()
                        + "): it does not override getHeightOfOrbit, so it has no height of "
                        + "orbit to report. Answering a default would be a fabricated value.");
    }

    /**
     * Set height of orbit - Geostationary satellite projection, the {@code +h} of
     * {@code 9.8.1:src/projections/geos.cpp}, {@code nsper.cpp} and {@code tpers.cpp}.
     * <p>
     * The base class refuses, because it has no orbit. Only
     * {@code GeostationarySatelliteProjection} ({@code +proj=geos}) overrides this. Notably
     * {@code PerspectiveProjection} ({@code +proj=nsper}) does <em>not</em>, even though PROJ's
     * {@code nsper} reads {@code +h} and requires it — so {@code +proj=nsper +h=1000000} is
     * refused here, and that refusal is the honest answer: silently ignoring {@code +h} would
     * project from a default orbit and return a plausible, wrong coordinate.
     * <p>
     * <b>Behaviour change.</b> This used to throw a bare {@link java.util.NoSuchElementException NoSuchElementException} with
     * no message — unchecked, and not a {@link Proj4jException}, so it escaped every
     * {@code catch (Proj4jException)} in the library. See
     * {@link #setSouthernHemisphere(boolean)} for the full rationale, the measured golden-master
     * rows, and why the PROJ-compatible "retain and ignore" behaviour, if wanted, has to live in
     * {@code Proj4Parser} rather than here.
     *
     * @param h Height of orbit, in metres
     * @throws UnsupportedParameterException always, on the base class
     */
    public void setHeightOfOrbit(double h){
        throw new UnsupportedParameterException(ErrorCause.PROJECTION_NOT_IMPLEMENTED,
                "+h=" + h + " is not supported by projection " + getName() + " ("
                        + getClass().getName() + "): it does not override setHeightOfOrbit. "
                        + "PROJ retains and ignores a parameter no operator reads, so this "
                        + "definition is one PROJ accepts; Proj4J refuses it because it cannot "
                        + "tell an inapplicable +h from an unimplemented one.");
    }

    /**
     * Initialize the projection. This should be called after setting parameters and before using the projection.
     * This is for performance reasons as initialization may be expensive.
     */
    public void initialize() {
        spherical = (e == 0.0);
        one_es = 1-es;
        rone_es = 1.0/one_es;
        totalScale = a * fromMetres;
        // ell_set.cpp:618 -- `P->ra = 1. / P->a;` -- computed once, here, from whatever `a` is at
        // this moment. See totalScaleReciprocal: the inverse multiplies by it and must not divide.
        totalScaleReciprocal = 1.0 / totalScale;
        totalFalseEasting = falseEasting * fromMetres;
        totalFalseNorthing = falseNorthing * fromMetres;
    }

    public static float normalizeLongitude(float angle) {
        if ( Double.isInfinite(angle) || Double.isNaN(angle) )
            throw new InvalidValueException("Infinite or NaN longitude");
        while (angle > 180)
            angle -= 360;
        while (angle < -180)
            angle += 360;
        return angle;
    }

    public static double normalizeLongitudeRadians( double angle ) {
        if ( Double.isInfinite(angle) || Double.isNaN(angle) )
            throw new InvalidValueException("Infinite or NaN longitude");
        while (angle > Math.PI)
            angle -= ProjectionMath.TWOPI;
        while (angle < -Math.PI)
            angle += ProjectionMath.TWOPI;
        return angle;
    }

    public void setGamma(double gamma) {
        // no-op, overridden for Oblique Mercator
    }
    
    public void setGammaDegrees(double gamma) {
    	setGamma(DTR * gamma);
    }

    public void setNoUoff(boolean no_uoff) {
        // no-op, overridden for Oblique Mercator
    }

    /** Is this "projection" longlat? Overridden in LongLatProjection. */
    public Boolean isGeographic() {
        return false;
    }

    /**
     * Represents quality between possible outputs of {@link #project(ProjCoordinate, ProjCoordinate) }.
     * Subclasses of Projection should capture additional state that is used in the project method and delgate to base.
     *
     * Note: The name of the projection is not part of equality.
     */
    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that instanceof Projection) {
            Projection p = (Projection) that;
            return (
                // class represents implementation of project method
                this.getClass().equals(that.getClass()) &&
                ellipsoid.isEqual(p.ellipsoid) &&
                // a is settable independently of the ellipsoid by setRadius (i.e. by +R=), so
                // two projections can share an ellipsoid and still project differently; es is
                // zeroed by a handful of initialize() implementations for the same reason.
                a == p.a &&
                es == p.es &&
                falseNorthing == p.falseNorthing &&
                falseEasting == p.falseEasting &&
                scaleFactor == p.scaleFactor &&
                fromMetres == p.fromMetres &&
                trueScaleLatitude == p.trueScaleLatitude &&
                projectionLatitude == p.projectionLatitude &&
                projectionLongitude == p.projectionLongitude &&
                projectionLatitude1 == p.projectionLatitude1 &&
                projectionLatitude2 == p.projectionLatitude2 &&
                // Using Double.compare because alpha and lonc default to NaN and two
                // projections that both left them unset should still compare equal
                Double.compare(alpha, p.alpha) == 0 &&
                Double.compare(lonc, p.lonc) == 0 &&
                minLatitude == p.minLatitude &&
                maxLatitude == p.maxLatitude &&
                minLongitude == p.minLongitude &&
                maxLongitude == p.maxLongitude &&
                axes.equals(p.axes) &&
                // getUnits() rather than the unit field: unit is null unless +units= was given
                // or LongLatProjection.initialize() ran, so unit.equals(..) threw NPE for any
                // projected CRS defined without +units=.
                Objects.equals(getUnits(), p.getUnits()) &&
                primeMeridian.equals(p.primeMeridian));
        }
        return false;
    }

    /**
     * Hash of those fields considered in Projection equalituy.
     * Subclasses that override equality should override hashCode.
     */
    @Override
    public int hashCode() {
        // A manual chain rather than Objects.hash: this runs on every transform-cache lookup,
        // and Objects.hash allocates an Object[] and boxes every double. Deliberately not
        // memoised into a field -- the setters on this class are public, so a cached hash would
        // be both stale and a data race.
        int h = this.getClass().hashCode();
        // Consistent with equals, which compares ellipsoids by Ellipsoid.isEqual -- that is, on
        // equator radius and eccentricity squared only, not on name or short name.
        h = 31 * h + hash(ellipsoid.getEquatorRadius());
        h = 31 * h + hash(ellipsoid.getEccentricitySquared());
        h = 31 * h + hash(a);
        h = 31 * h + hash(es);
        h = 31 * h + hash(falseNorthing);
        h = 31 * h + hash(falseEasting);
        h = 31 * h + hash(scaleFactor);
        h = 31 * h + hash(fromMetres);
        h = 31 * h + hash(trueScaleLatitude);
        h = 31 * h + hash(projectionLatitude);
        h = 31 * h + hash(projectionLongitude);
        h = 31 * h + hash(projectionLatitude1);
        h = 31 * h + hash(projectionLatitude2);
        h = 31 * h + hash(alpha);
        h = 31 * h + hash(lonc);
        h = 31 * h + hash(minLatitude);
        h = 31 * h + hash(maxLatitude);
        h = 31 * h + hash(minLongitude);
        h = 31 * h + hash(maxLongitude);
        h = 31 * h + axes.hashCode();
        h = 31 * h + getUnits().hashCode();
        h = 31 * h + primeMeridian.hashCode();
        return h;
    }

    /**
     * Hash of a double that agrees with {@code ==}. {@code -0.0 == 0.0} is true but the two have
     * different bit patterns, so negative zero is normalised before hashing. NaN is left alone:
     * {@link Double#hashCode(double)} canonicalises every NaN to the same value, which matches the
     * {@code Double.compare} treatment of the NaN-defaulted parameters in
     * {@link #equals(Object)}.
     */
    private static int hash(double value) {
        return Double.hashCode(value == 0.0 ? 0.0 : value);
    }
}

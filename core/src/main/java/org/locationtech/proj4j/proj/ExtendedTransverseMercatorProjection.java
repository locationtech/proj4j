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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.AuxLat;
import org.locationtech.proj4j.util.Clenshaw6;
import org.locationtech.proj4j.util.MathHelpers;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The Poder/Engsager "exact" transverse Mercator, {@code +proj=etmerc} —
 * {@code 9.8.1:src/projections/tmerc.cpp}'s {@code exact_e_fwd} / {@code exact_e_inv}.
 *
 * <p>This is also what PROJ 9.8.1 uses for plain {@code +proj=tmerc} and for {@code +proj=utm}
 * by default; see {@link TransverseMercatorProjection}, which owns that dispatch. The
 * Evenden/Snyder series lives there too.
 *
 * <h2>Accuracy</h2>
 *
 * <p>Uniformly about 1 nm anywhere inside {@code |Ce| <= 2.623395162778} (150&deg; from the
 * central meridian), where the Evenden/Snyder series is 0.9 mm off at 6&deg; and unbounded
 * beyond 20&deg;. That is why {@code builtins.gie:1929-1959} can assert {@code etmerc} at
 * {@code tolerance 50 nm} including a row 3,900 km out.
 *
 * <h2>Restructured against 9.8.1</h2>
 *
 * <p>The bodies here were pre-9.x. Four changes, all from upstream:
 *
 * <ul>
 * <li><b>Six transcendental calls removed from the forward.</b> {@code sin(2*Cn)},
 *     {@code cos(2*Cn)}, {@code sinh(2*Ce)} and {@code cosh(2*Ce)} are now derived
 *     algebraically from quantities already in hand ({@code tmerc.cpp:341-369}), and the
 *     second {@code atan2} plus the {@code tan} are replaced by one reciprocal
 *     ({@code :313-325}).
 * <li><b>The inverse's {@code sinh}/{@code cosh} pair becomes one {@code exp}</b>
 *     ({@code :401-404}), and its {@code atan(sinh(Ce))} + {@code sin} + {@code cos} +
 *     {@code atan2} chain collapses to {@code atan2(sinhCe, cos_Cn)} ({@code :434-438}).
 * <li><b>The final Gaussian&rarr;geodetic step is fed the sine and cosine it already has</b>
 *     ({@code :442-443}) instead of re-deriving them from the angle. That is strictly more
 *     accurate near the poles and is what lets {@code builtins.gie:7123} pass at 50 nm.
 * <li><b>Out-of-domain is an exception, not a plausible coordinate.</b> See below.
 * </ul>
 *
 * <h2>The fail-open inverse</h2>
 *
 * <p>{@link #projectInverse} used to have <b>no {@code else} branch</b>. Because the caller
 * passes the same object as source and destination, an easting outside
 * {@code |Ce| <= 2.623395162778} left {@code out} holding the <em>input metres</em>, which
 * {@link Projection#inverseProjectRadians} then read as radians and clamped to &plusmn;&pi;.
 * An out-of-zone UTM inverse therefore returned a finite, plausible, entirely wrong lon/lat —
 * invisible to a finiteness postcondition, because the output <em>is</em> finite. Both
 * directions now throw {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN}, matching
 * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} at {@code tmerc.cpp:379} and
 * {@code :447}.
 *
 * @see <a href="https://github.com/OSGeo/proj.4/issues/316">Proj.4 issue 316</a>
 */
public class ExtendedTransverseMercatorProjection extends CylindricalProjection {

    private static final long serialVersionUID = 1L;

    /**
     * 150&deg; from the central meridian in normalised easting, {@code tmerc.cpp:372}. Beyond
     * it the Koenig/Weise series is not merely inaccurate, it is divergent.
     */
    static final double CE_LIMIT = 2.623395162778;

    double Qn;    /* Merid. quad., scaled to the projection */
    double Zb;    /* Radius vector in polar coord. systems  */
    double[] cgb = new double[6]; /* Constants for Gauss -> Geo lat */
    double[] cbg = new double[6]; /* Constants for Geo lat -> Gauss */
    double[] utg = new double[6]; /* Constants for transv. merc. -> geo */
    double[] gtu = new double[6]; /* Constants for geo -> transv. merc. */

    /**
     * {@code cgb} as a fixed-order-6 Clenshaw evaluator: the real-argument summation
     * {@code gatg} performs, but without the {@code cos(2B)}/{@code sin(2B)} pair.
     */
    private Clenshaw6 cgbSeries;

    /** {@code cbg} as a fixed-order-6 Clenshaw evaluator. */
    private Clenshaw6 cbgSeries;

    /** {@code gtu} as a fixed-order-6 real Clenshaw evaluator, used only for {@code Zb}. */
    private Clenshaw6 gtuSeries;

    /**
     * Indicates whether a Southern Hemisphere UTM zone
     */
    protected boolean isSouth = false;

    public ExtendedTransverseMercatorProjection() {
        // setEllipsoid, not an assignment to the field: Projection's own constructor has already
        // run setEllipsoid(Ellipsoid.SPHERE), so assigning `ellipsoid` alone left a, e and es
        // describing the *sphere* that constant names, while the field said GRS80 -- and
        // Poder/Engsager is undefined on a sphere, so Registry.getProjection("etmerc") produced an
        // instance that could not project. Every caller in the library goes on to call
        // setEllipsoid again from the parser, so this only fixes the un-parsed case.
        setEllipsoid(Ellipsoid.GRS80);
        projectionLatitude = ProjectionMath.toRad(0);
        projectionLongitude = ProjectionMath.toRad(0);
        minLongitude = ProjectionMath.toRad(-90);
        maxLongitude = ProjectionMath.toRad(90);
        initialize();
    }

    public ExtendedTransverseMercatorProjection(Ellipsoid ellipsoid, double lon_0, double lat_0, double k, double x_0, double y_0) {
        setEllipsoid(ellipsoid);
        projectionLongitude = lon_0;
        projectionLatitude = lat_0;
        scaleFactor = k;
        falseEasting = x_0;
        falseNorthing = y_0;
        initialize();
    }

    /**
     * Builds a Poder/Engsager kernel that shares the figure of the Earth, the latitude of origin
     * and the scale factor of another transverse-Mercator projection, for
     * {@link TransverseMercatorProjection} to delegate to.
     *
     * <p>Only those three quantities enter {@code setup_exact}. The false easting and northing,
     * the unit and {@code totalScale} are deliberately <em>not</em> copied: the delegate is
     * driven through its raw {@link #project} / {@link #projectInverse} kernel, which works in
     * units of the semi-major axis, and the affine part is applied once by the outer
     * projection's own {@code projectRadians}.
     *
     * @param p the projection to mirror
     * @return a fully initialised kernel
     * @throws InvalidValueException if {@code p} is on a sphere
     */
    static ExtendedTransverseMercatorProjection forDelegation(Projection p) {
        return new ExtendedTransverseMercatorProjection(p);
    }

    /**
     * The delegation constructor. Copies the raw {@code a}/{@code e}/{@code es} triple rather
     * than going through {@link #setEllipsoid}, because {@code +R=} sets the size independently
     * of the ellipsoid object and the two can disagree.
     */
    private ExtendedTransverseMercatorProjection(Projection p) {
        ellipsoid = p.ellipsoid;
        a = p.a;
        e = p.e;
        es = p.es;
        projectionLatitude = p.projectionLatitude;
        projectionLongitude = p.projectionLongitude;
        scaleFactor = p.scaleFactor;
        initialize();
    }

    @Override
    public void setSouthernHemisphere(boolean isSouth) {
        this.isSouth = isSouth;
    }

    @Override
    public boolean getSouthernHemisphere() {
        return isSouth;
    }

    /**
     * Complex Clenshaw summation, {@code tmerc.cpp:266-291}. The four trigonometric and
     * hyperbolic values of the doubled arguments are supplied by the caller, because 9.8.1's
     * forward derives all four algebraically rather than calling {@code sin}, {@code cos},
     * {@code sinh} and {@code cosh}.
     *
     * @param a   six coefficients
     * @param out receives the real part at index 0 and the imaginary part at index 1
     */
    static void clenS(double[] a, double sin_arg_r, double cos_arg_r,
                      double sinh_arg_i, double cosh_arg_i, double[] out) {
        double r = 2.0 * cos_arg_r * cosh_arg_i;
        double i = -2.0 * sin_arg_r * sinh_arg_i;

        double hr = a[AuxLat.ORDER - 1];
        double hi = 0.0;
        double hr1 = 0.0;
        double hi1 = 0.0;
        double hr2;
        double hi2;
        for (int p = AuxLat.ORDER - 1; p > 0; ) {
            hr2 = hr1;
            hi2 = hi1;
            hr1 = hr;
            hi1 = hi;
            --p;
            hr = -hr2 + r * hr1 - i * hi1 + a[p];
            hi = -hi2 + i * hr1 + r * hi1;
        }

        r = sin_arg_r * cosh_arg_i;
        i = cos_arg_r * sinh_arg_i;
        out[0] = r * hr - i * hi;
        out[1] = r * hi + i * hr;
    }

    /**
     * {@code exact_e_fwd}, {@code tmerc.cpp:300-384}.
     *
     * <p><b>Math, not StrictMath.</b> Deliberate, and only until the library-wide
     * {@code StrictMath} sweep lands: that change is a separate step in the plan precisely so
     * that its cost is not attributed to the algorithm work here. One ulp of a radian is about
     * 2 pm on the ground, eight orders inside the 50 nm bar these rows carry.
     */
    public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
        requireSetup();

        /* ell. LAT, LNG -> Gaussian LAT, LNG */
        double Cn = cbgSeries.convert(lpphi);

        /* Gaussian LAT, LNG -> compl. sph. LAT */
        final double sin_Cn = Math.sin(Cn);
        final double cos_Cn = Math.cos(Cn);
        final double sin_Ce = Math.sin(lplam);
        final double cos_Ce = Math.cos(lplam);

        final double cos_Cn_cos_Ce = cos_Cn * cos_Ce;
        Cn = Math.atan2(sin_Cn, cos_Cn_cos_Ce);

        final double inv_denom_tan_Ce = 1.0 / MathHelpers.norm2(sin_Cn, cos_Cn_cos_Ce);
        final double tan_Ce = sin_Ce * cos_Cn * inv_denom_tan_Ce;

        /* compl. sph. N, E -> ell. norm. N, E */
        /* Replaces: Ce = log(tan(FORTPI + Ce*0.5)) */
        double Ce = MathHelpers.asinh(tan_Ce);

        /*
         * sin(2*Cn), cos(2*Cn), sinh(2*Ce) and cosh(2*Ce) without a single transcendental
         * call, tmerc.cpp:327-369. From sin(2u) = 2 sin u cos u, cos(2u) = 2cos^2 u - 1,
         * sin(atan y) = y/sqrt(1+y^2), cos(atan y) = 1/sqrt(1+y^2), sinh(asinh y) = y and
         * cosh(asinh y) = sqrt(1+y^2), plus the identity
         *   1 + tan_Ce^2 = 1/(sin_Cn^2 + cos_Cn^2 cos_Ce^2) = inv_denom_tan_Ce^2.
         */
        final double two_inv_denom = 2.0 * inv_denom_tan_Ce;
        final double two_inv_denom_square = two_inv_denom * inv_denom_tan_Ce;
        final double tmp_r = cos_Cn_cos_Ce * two_inv_denom_square;
        final double sin_arg_r = sin_Cn * tmp_r;
        final double cos_arg_r = cos_Cn_cos_Ce * tmp_r - 1.0;
        final double sinh_arg_i = tan_Ce * two_inv_denom;
        final double cosh_arg_i = two_inv_denom_square - 1.0;

        final double[] dC = new double[2];
        clenS(gtu, sin_arg_r, cos_arg_r, sinh_arg_i, cosh_arg_i, dC);
        Cn += dC[0];
        Ce += dC[1];

        if (Math.abs(Ce) <= CE_LIMIT) {
            xy.y = Qn * Cn + Zb;  /* Northing */
            xy.x = Qn * Ce;       /* Easting  */
        } else {
            /*
             * tmerc.cpp:379 sets PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN here. proj4j
             * used to write HUGE_VAL into both ordinates and return normally.
             */
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "longitude " + Math.toDegrees(lplam) + " deg from the central meridian is "
                            + "outside the projection domain: normalised easting " + Ce
                            + " exceeds " + CE_LIMIT + " (150 deg)");
        }
        return xy;
    }

    /**
     * {@code exact_e_inv}, {@code tmerc.cpp:387-451}.
     *
     * @throws ProjectionException with {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} when the
     *         easting is more than 150&deg; of arc from the central meridian — the case that
     *         used to return the input metres reinterpreted as radians
     */
    public ProjCoordinate projectInverse(double x, double y, ProjCoordinate out) {
        requireSetup();

        /* normalize N, E */
        double Cn = (y - Zb) / Qn;
        double Ce = x / Qn;

        if (!(Math.abs(Ce) <= CE_LIMIT)) { /* 150 degrees; inverted so NaN is rejected */
            throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
                    "easting " + x + " is outside the projection domain: normalised easting "
                            + Ce + " exceeds " + CE_LIMIT + " (150 deg from the central "
                            + "meridian)");
        }

        /* norm. N, E -> compl. sph. LAT, LNG */
        final double sin_arg_r = Math.sin(2.0 * Cn);
        final double cos_arg_r = Math.cos(2.0 * Cn);

        /* One exp instead of sinh(2*Ce) and cosh(2*Ce), tmerc.cpp:401-404. */
        final double exp_2_Ce = Math.exp(2.0 * Ce);
        final double half_inv_exp_2_Ce = 0.5 / exp_2_Ce;
        final double sinh_arg_i = 0.5 * exp_2_Ce - half_inv_exp_2_Ce;
        final double cosh_arg_i = 0.5 * exp_2_Ce + half_inv_exp_2_Ce;

        final double[] dC = new double[2];
        clenS(utg, sin_arg_r, cos_arg_r, sinh_arg_i, cosh_arg_i, dC);
        Cn += dC[0];
        Ce += dC[1];

        /* compl. sph. LAT -> Gaussian LAT, LNG */
        final double sin_Cn = Math.sin(Cn);
        final double cos_Cn = Math.cos(Cn);

        /*
         * Dividing both arguments of the two atan2 calls by cos_Ce, which is positive:
         *   Ce = atan2(tan_Ce, cos_Cn)             = atan2(sinh(Ce), cos_Cn)
         *   Cn = atan2(sin_Cn, hypot(tan_Ce, cos_Cn))
         * removes one atan, one sin and one cos (tmerc.cpp:420-438).
         */
        final double sinhCe = Math.sinh(Ce);
        Ce = Math.atan2(sinhCe, cos_Cn);
        final double modulus_Ce = MathHelpers.norm2(sinhCe, cos_Cn);
        final double rr = MathHelpers.norm2(sin_Cn, modulus_Ce);
        Cn = Math.atan2(sin_Cn, modulus_Ce);

        /*
         * Gaussian LAT, LNG -> ell. LAT, LNG. The sine and cosine of the Gaussian latitude are
         * already known exactly as sin_Cn/rr and modulus_Ce/rr, so they are handed to the
         * series rather than recovered from the angle -- full relative accuracy at the pole,
         * which is what builtins.gie:7123 (50 nm at 89.99135 deg) measures.
         */
        out.y = cgbSeries.convert(Cn, sin_Cn / rr, modulus_Ce / rr);
        out.x = Ce;
        return out;
    }

    /**
     * Fails closed if the coefficients were never built — the only way to reach this is to use an
     * instance that was constructed but never given an ellipsoid, which used to run the series
     * with {@code Qn = 0} and produce infinities.
     */
    private void requireSetup() {
        if (cbgSeries == null) {
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE, toString()
                    + ": no ellipsoid has been set, so the Poder/Engsager coefficients do not "
                    + "exist. Call setEllipsoid(..) then initialize().");
        }
    }

    public void setUTMZone(int zone) {
        zone--;
        projectionLongitude = (zone + .5) * Math.PI / 30. - Math.PI;
        projectionLatitude = 0.0;
        scaleFactor = 0.9996;
        falseEasting = 500000;
        falseNorthing = isSouth ? 10000000.0 : 0.0;
        initialize();
    }

    public void initialize() {
        super.initialize();
        initializeExact();
    }

    /**
     * Builds the Poder/Engsager coefficients, {@code setup_exact} at {@code tmerc.cpp:456-490}.
     *
     * <p>Split out of {@link #initialize()} so that {@link TransverseMercatorProjection} — where
     * a sphere is legal and selects the Evenden/Snyder series instead — can build them only when
     * they exist.
     *
     * <h4>A sphere is an error here, unconditionally</h4>
     *
     * <p>Both {@code +proj=etmerc} ({@code tmerc.cpp:617-625}) and {@code +proj=utm}
     * ({@code :666-671}) reject {@code es == 0} with
     * {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}; {@code builtins.gie:7777} asserts it for
     * {@code +proj=utm +a=6400000 +zone=30}. Proj4J used to return silently from setup and then
     * run the series with {@code Qn = 0}, which is the silent-wrong-answer shape this whole class
     * of fix exists to remove — so the guard is unconditional and stays that way.
     *
     * <p><b>The eccentricity test is ordered before the algorithm choice, not after</b>, exactly
     * as {@code setup()} does at {@code tmerc.cpp:518-519}: a sphere never reaches Poder/Engsager
     * at all, because {@link TransverseMercatorProjection#initialize()} takes its spherical branch
     * and never constructs this class. {@code +algo} then selects only among the <em>ellipsoidal</em>
     * algorithms, which is why {@code +algo=poder_engsager} on a sphere is not an error upstream —
     * it is silently downgraded.
     *
     * <p>What did have to be fixed to make that hold is the <em>constructor</em>:
     * {@link Projection#Projection()} runs {@code setEllipsoid(Ellipsoid.SPHERE)}, and this
     * class's no-argument constructor used to assign the {@code ellipsoid} field directly, so a
     * {@code Registry}-instantiated instance carried {@code es == 0} before the parser had said
     * anything. It now calls {@code setEllipsoid(Ellipsoid.GRS80)}.
     *
     * @throws InvalidValueException if the figure of the Earth is a sphere
     */
    protected void initializeExact() {
        if (es <= 0.0) {
            // Fail closed rather than leaving usable-looking zeros behind.
            cgbSeries = null;
            cbgSeries = null;
            gtuSeries = null;
            Qn = Double.NaN;
            Zb = Double.NaN;
            throw new InvalidValueException(ErrorCause.INVALID_PARAM_VALUE,
                    toString() + ": the Poder/Engsager transverse Mercator requires an "
                            + "ellipsoid; eccentricity must not be zero. Use +proj=tmerc, "
                            + "which selects the Evenden/Snyder series on a sphere.");
        }

        /*
         * Third flattening. Previously derived in two steps as
         *   f = es / (1 + sqrt(1 - es));  n = f / (2 - f);
         * which is algebraically es / (1 + sqrt(1-es))^2 but carries one extra rounding.
         * AuxLat.thirdFlattening is the direct form -- a single exactly-rounded sqrt, so
         * every coefficient below is bit-reproducible across x86-64 and AArch64.
         */
        final double n = AuxLat.thirdFlattening(es);

        /*
         * COEF. OF TRIG SERIES GEO <-> GAUSS, PROJ_ETMERC_ORDER = 6th degree
         * (Engsager and Poder, ICC2007). These four coefficient sets were verified term by
         * term against 9.8.1's pj_auxlat_coeffs table (src/latitudes.cpp) and are bit-identical
         * to it; the Gaussian latitude of Poder/Engsager *is* Karney's conformal latitude chi,
         * so:
         *
         *   cgb := Gaussian -> Geodetic, KW p190-191 (61)-(62)  ==  C[phi,chi]
         *   cbg := Geodetic -> Gaussian, KW p186-187 (51)-(52)  ==  C[chi,phi]
         *   utg := ell. N,E -> sph. N,E, KW p194 (65)           ==  C[chi,mu]
         *   gtu := sph. N,E -> ell. N,E, KW p196 (69)           ==  C[mu,chi]
         */
        AuxLat.coeffs(n, AuxLat.CONFORMAL, AuxLat.GEOGRAPHIC, cgb);
        AuxLat.coeffs(n, AuxLat.GEOGRAPHIC, AuxLat.CONFORMAL, cbg);
        AuxLat.coeffs(n, AuxLat.RECTIFYING, AuxLat.CONFORMAL, utg);
        AuxLat.coeffs(n, AuxLat.CONFORMAL, AuxLat.RECTIFYING, gtu);

        cgbSeries = new Clenshaw6(cgb);
        cbgSeries = new Clenshaw6(cbg);
        gtuSeries = new Clenshaw6(gtu);

        /* Constants of the projections */
        /* Transverse Mercator (UTM, ITM, etc) */
        /* Norm. mer. quad, K&W p.50 (96), p.19 (38b), p.5 (2) */
        /* 9.8.1:tmerc.cpp:482 associates this as k0 * rectifyingRadius(n). */
        Qn = scaleFactor * AuxLat.rectifyingRadius(n);

        /* Gaussian latitude value of the origin latitude */
        final double Z = cbgSeries.convert(projectionLatitude);

        /* Origin northing minus true northing at the origin latitude */
        /* i.e. true northing = N - P->Zb                         */
        Zb = -Qn * gtuSeries.convert(Z);
    }

    public boolean hasInverse() {
        return true;
    }

    public boolean isRectilinear() {
        return false;
    }

    public Object clone() {
        ExtendedTransverseMercatorProjection p = (ExtendedTransverseMercatorProjection) super.clone();
        if (cgb != null) {
            p.cgb = (double[]) cgb.clone();
        }
        if (cbg != null) {
            p.cbg = (double[]) cbg.clone();
        }
        if (utg != null) {
            p.utg = (double[]) utg.clone();
        }
        if (gtu != null) {
            p.gtu = (double[]) gtu.clone();
        }
        // The Clenshaw6 evaluators are immutable, so the shallow copies are safe -- but they
        // must not be left pointing at the *clone's* freshly copied arrays, which they do not:
        // Clenshaw6 copies the six coefficients out at construction and retains no reference.
        return p;
    }

    public String toString() {
        return "Extended Transverse Mercator";
    }

}

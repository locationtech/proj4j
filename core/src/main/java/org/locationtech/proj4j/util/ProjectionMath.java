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

package org.locationtech.proj4j.util;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjectionException;


public class ProjectionMath {

    public final static double PI = Math.PI;

    public final static double HALFPI = Math.PI / 2.0;

    public final static double QUARTERPI = Math.PI / 4.0;

    public final static double FORTPI = QUARTERPI;

    public final static double TWOPI = Math.PI * 2.0;

    public final static double RTD = 180.0 / Math.PI;
    
    public final static double DTR = Math.PI / 180.0;

    public final static double EPS10 = 1.0e-10;

    /**
     * Degrees to radians, {@code deg * DEG_TO_RAD} — PROJ's dominant idiom
     * ({@code 9.8.1:src/proj_internal.h:1034}, used at 147 sites in 24 files).
     *
     * <p><b>Use this, never {@link Math#toRadians}, on any path whose value reaches a coordinate,
     * a grid index, a comparison or a stored parameter.</b> {@link Math#toRadians} is <b>not
     * bit-stable across Java versions</b>: Java 8 evaluates {@code angdeg / 180.0 * PI}, Java 9
     * and later multiply by a precomputed constant. Measured over the 721 whole degrees in
     * {@code [-360, 360]}, with the input set pinned by digest so a moved argument could not be
     * mistaken for a moved function:
     *
     * <table border="1">
     * <caption>1-ULP disagreement out of 721 whole degrees</caption>
     * <tr><th>pair</th><th>Temurin 8u502 x64</th><th>11.0.32 aarch64 / x64, 21.0.11 aarch64</th></tr>
     * <tr><td>{@code Math.toRadians(d)} vs {@code d * DTR}</td><td><b>182</b> (25.24%)</td><td>0</td></tr>
     * <tr><td>{@code Math.toRadians(d)} vs {@code d * PI / 180.0}</td><td>196</td><td>186</td></tr>
     * </table>
     *
     * <p>A library whose output depends on which JVM it happens to run under cannot be
     * reproducible, and this one promises bit-for-bit determinism across executors. 1 ULP of a
     * radian is about 1.4e-9 m on the ellipsoid, which is exactly the bar of the 16
     * {@code nm}-tolerance rows in the gie corpus, so this is a conformance question and not only
     * a tidiness one.
     *
     * <h4>Why {@code DTR} and not the {@code PJ_TORAD} macro</h4>
     *
     * <p>PROJ has <b>two</b> degree/radian idioms and the macro is the minority one:
     *
     * <ul>
     * <li>{@code DEG_TO_RAD} / {@code RAD_TO_DEG}, decimal literals at
     *     {@code proj_internal.h:1033-1034} — <b>147 and 33 occurrences</b>. Both literals are
     *     <b>bit-identical</b> to Java's {@code Math.PI / 180.0} and {@code 180.0 / Math.PI},
     *     verified by hex comparison, so {@link #DTR} <em>is</em> {@code DEG_TO_RAD}.</li>
     * <li>{@code PJ_TORAD(deg) = (deg) * M_PI / 180.0} and {@code PJ_TODEG} at
     *     {@code proj_internal.h:92-95} — only <b>7</b> and <b>11</b> occurrences.</li>
     * </ul>
     *
     * <p>These are different expressions: {@code deg * DTR} multiplies by a once-rounded
     * {@code PI/180}, whereas {@code PJ_TORAD} rounds {@code deg * PI} and then divides. They
     * disagree on <b>186 of 721</b> whole degrees on every JVM measured. Adopting the macro form
     * here was tried and <b>measurably reduced</b> agreement with PROJ, because the projections
     * that actually convert at this boundary use {@code DEG_TO_RAD}: against {@code proj 9.8.1}
     * over 1,187 in-domain points, {@code +proj=gnom} went from 204 to 148 bit-exact matches and
     * its mean deviation rose from 1.50e-9 m to 1.82e-9 m. With {@code DTR} it improves instead,
     * to <b>214</b> bit-exact and 1.43e-9 m. See {@code gnom.cpp:124-131} and
     * {@code aeqd.cpp:110-116}, which write {@code lp.phi / DEG_TO_RAD} and {@code azi1 *=
     * DEG_TO_RAD} — not the macros.
     *
     * <p>{@code core}'s use of this is enforced by
     * {@code org.locationtech.proj4j.determinism.NoJdkAngleConversionTest}.
     *
     * @param deg an angle in degrees
     * @return the angle in radians, bit-identical to PROJ's {@code deg * DEG_TO_RAD}
     * @see #toDeg(double)
     * @since 1.5.0
     */
    public static double toRad(double deg) {
        return deg * DTR;
    }

    /**
     * Radians to degrees, {@code rad / DEG_TO_RAD} — the form PROJ uses at this boundary
     * ({@code gnom.cpp:124}, {@code aeqd.cpp:110-113,217-219}, and 22 other files).
     *
     * <p><b>A division, deliberately, and not {@code rad * RTD}.</b> {@code x * (1/s)} is not
     * {@code x / s}; the two disagree on <b>46 of the 721</b> sample values on <em>every</em> JVM
     * measured. PROJ writes the divide in the projection files that convert at the GeographicLib
     * boundary, so the divide is what fidelity requires here. It is also the better-conditioned
     * choice for round-tripping a stored parameter: over 735 degree values typical of CRS
     * definitions, {@code (deg * DTR) / DTR} fails to recover {@code deg} exactly 51 times,
     * against 79 for {@code (deg * DTR) * RTD} and 158 for the {@code PJ_TODEG} macro form.
     *
     * <h4>{@code toDegrees} needed the same treatment as {@code toRadians} — but not for the
     * reason the reference material gave</h4>
     *
     * <p>The received note was that {@code PJ_TODEG} <em>is</em> bit-identical to
     * {@link Math#toDegrees} while {@code PJ_TORAD} is not, and therefore that only the radian
     * direction needed fixing. <b>That is a Java-8-era fact which stopped being true at Java 9.</b>
     * Java 8's body is literally {@code angrad * 180.0 / PI}, the same association as the macro;
     * Java 9 replaced it with a constant multiply. Measured over the radian images of the same
     * 721 whole degrees:
     *
     * <table border="1">
     * <caption>1-ULP disagreement out of 721</caption>
     * <tr><th>pair</th><th>Temurin 8u502 x64</th><th>11.0.32 / 21.0.11</th></tr>
     * <tr><td>{@code Math.toDegrees(r)} vs {@code r / DTR}</td><td><b>178</b></td><td><b>46</b></td></tr>
     * <tr><td>{@code Math.toDegrees(r)} vs {@code r * RTD}</td><td>180</td><td>0</td></tr>
     * <tr><td>{@code r * RTD} vs {@code r / DTR}</td><td>46</td><td>46</td></tr>
     * </table>
     *
     * <p>So {@link Math#toDegrees} is version-unstable in exactly the way {@link Math#toRadians}
     * is, and both directions did need replacing. The asymmetry that survives is a different one:
     * for the radian direction {@code DTR} multiplication happens to coincide with Java 9+'s
     * behaviour, so that half of the change is a no-op on a modern JVM and purely repairs Java 8;
     * the degree direction moves bits on <em>every</em> JVM, by 46 of 721, because PROJ divides
     * where Java multiplies.
     *
     * @param rad an angle in radians
     * @return the angle in degrees, bit-identical to PROJ's {@code rad / DEG_TO_RAD}
     * @see #toRad(double)
     * @since 1.5.0
     */
    public static double toDeg(double rad) {
        return rad / DTR;
    }

    /**
     * Degree versions of trigonometric functions
     */
    public static double sind(double v) {
        return Math.sin(v * DTR);
    }

    public static double cosd(double v) {
        return Math.cos(v * DTR);
    }

    public static double tand(double v) {
        return Math.tan(v * DTR);
    }

    public static double asind(double v) {
        return Math.asin(v) * RTD;
    }

    public static double acosd(double v) {
        return Math.acos(v) * RTD;
    }

    public static double atand(double v) {
        return Math.atan(v) * RTD;
    }

    public static double atan2d(double y, double x) {
        return Math.atan2(y, x) * RTD;
    }

    /**
     * PROJ's {@code ONE_TOL} ({@code 9.8.1:src/aasincos.cpp:8}): how far past 1 the argument of an
     * inverse trigonometric function may be before it stops being rounding noise.
     *
     * @since 1.5.0
     */
    public static final double ONE_TOL = 1.00000000000001;

    /**
     * Arc sine, clamping silently and passing {@code NaN} through.
     *
     * @param v the sine
     * @return the angle, radians
     * @deprecated superseded by {@link #asinChecked(double)}. Two defects, both silent:
     *             <b>{@code NaN} is not clamped at all</b>, because {@code Math.abs(NaN) > 1.}
     *             is {@code false}, so a {@code NaN} argument passes straight through and
     *             becomes a {@code NaN} coordinate; and there is <b>no tolerance band</b>, so
     *             {@code asin(1e9)} is {@code pi/2} where PROJ raises past
     *             {@link #ONE_TOL}. Retained only because it is public API.
     */
    @Deprecated
    public static double asin(double v) {
        if (Math.abs(v) > 1.)
            return v < 0.0 ? -Math.PI / 2 : Math.PI / 2;
        return Math.asin(v);
    }

    /**
     * Arc cosine, clamping silently and passing {@code NaN} through.
     *
     * @param v the cosine
     * @return the angle, radians
     * @deprecated superseded by {@link #acosChecked(double)}, for the same two reasons as
     *             {@link #asin(double)}. Retained only because it is public API.
     */
    @Deprecated
    public static double acos(double v) {
        if (Math.abs(v) > 1.)
            return v < 0.0 ? Math.PI : 0.0;
        return Math.acos(v);
    }

    /**
     * Square root, returning 0 for a negative argument.
     *
     * @param v the radicand
     * @return the root, or 0
     * @deprecated superseded by {@link #sqrtChecked(double)}. Upstream's {@code asqrt}
     *             ({@code 9.8.1:src/aasincos.cpp:33}) does the same thing, which is why the
     *             replacement is a new method rather than a change to this one — but 0 is a
     *             plausible coordinate, and a negative radicand of magnitude 1e-3 is not
     *             rounding noise. Retained because it is public API and because
     *             {@code asqrt}-equivalence is sometimes what a verbatim port needs.
     */
    @Deprecated
    public static double sqrt(double v) {
        return v < 0.0 ? 0.0 : Math.sqrt(v);
    }

    /**
     * PROJ's {@code aasin} ({@code 9.8.1:src/aasincos.cpp:11-21}) with its errno raised as an
     * exception: clamp to &plusmn;&pi;/2 within {@link #ONE_TOL}, raise beyond it.
     * <p>
     * The tolerance band is the point. {@code |v| >= 1} is reached constantly by rounding in a
     * projection kernel and clamping is correct there; {@code |v| > 1.00000000000001} is not
     * rounding, and PROJ sets
     * {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} for it. {@link #asin(double)} has
     * no band at all, so it answers {@code asin(1e9)} with {@code pi/2}.
     * <p>
     * <b>One deliberate divergence from upstream:</b> {@code NaN} raises here. In C,
     * {@code fabs(NaN) >= 1.} is false, so {@code aasin} returns {@code asin(NaN) = NaN} and the
     * failure travels as data. That is the shape this whole class of fix exists to remove.
     *
     * @param v the sine
     * @return the angle, radians, in {@code [-pi/2, pi/2]}
     * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} if
     *         {@code |v| > ONE_TOL}, {@link ErrorCause#NUMERICAL_FAILURE} if {@code v} is
     *         {@code NaN}
     * @since 1.5.0
     */
    public static double asinChecked(double v) {
        double av = Math.abs(v);
        if (Double.isNaN(v)) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE,
                    "asin(NaN): a NaN reached an inverse trigonometric function, which means an "
                            + "earlier step produced one silently");
        }
        if (av >= 1.0) {
            if (av > ONE_TOL) {
                throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN,
                        "asin(" + v + "): |v| exceeds ONE_TOL = " + ONE_TOL
                                + ", which is past rounding noise");
            }
            return v < 0.0 ? -HALFPI : HALFPI;
        }
        return Math.asin(v);
    }

    /**
     * PROJ's {@code aacos} ({@code 9.8.1:src/aasincos.cpp:23-32}) with its errno raised as an
     * exception: clamp to 0 or &pi; within {@link #ONE_TOL}, raise beyond it. See
     * {@link #asinChecked(double)} for why the band and the {@code NaN} rejection are there.
     *
     * @param v the cosine
     * @return the angle, radians, in {@code [0, pi]}
     * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} if
     *         {@code |v| > ONE_TOL}, {@link ErrorCause#NUMERICAL_FAILURE} if {@code v} is
     *         {@code NaN}
     * @since 1.5.0
     */
    public static double acosChecked(double v) {
        double av = Math.abs(v);
        if (Double.isNaN(v)) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE,
                    "acos(NaN): a NaN reached an inverse trigonometric function, which means an "
                            + "earlier step produced one silently");
        }
        if (av >= 1.0) {
            if (av > ONE_TOL) {
                throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN,
                        "acos(" + v + "): |v| exceeds ONE_TOL = " + ONE_TOL
                                + ", which is past rounding noise");
            }
            return v < 0.0 ? Math.PI : 0.0;
        }
        return Math.acos(v);
    }

    /**
     * Square root that raises on a negative radicand instead of answering 0.
     * <p>
     * Upstream's {@code asqrt} has no tolerance band and neither does this: there is no
     * defensible width for one, because the scale of a "negligible" negative radicand depends
     * entirely on the quantity being rooted. A caller whose expression genuinely produces
     * {@code -1e-18} from cancellation should write {@code Math.sqrt(Math.max(0.0, v))} and say
     * so at the call site, rather than have every call site silently share one guess.
     *
     * @param v the radicand
     * @return the root
     * @throws ProjectionException {@link ErrorCause#NUMERICAL_FAILURE} if {@code v} is negative
     *         or {@code NaN}
     * @since 1.5.0
     */
    public static double sqrtChecked(double v) {
        if (Double.isNaN(v) || v < 0.0) {
            throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE,
                    "sqrt(" + v + "): negative or NaN radicand; returning 0 here would be a "
                            + "plausible coordinate rather than an error");
        }
        return Math.sqrt(v);
    }

    public static double distance(double dx, double dy) {
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static double hypot(double x, double y) {
        if (x < 0.0)
            x = -x;
        else if (x == 0.0)
            return y < 0.0 ? -y : y;
        if (y < 0.0)
            y = -y;
        else if (y == 0.0)
            return x;
        if (x < y) {
            x /= y;
            return y * Math.sqrt(1.0 + x * x);
        } else {
            y /= x;
            return x * Math.sqrt(1.0 + y * y);
        }
    }

    public static double atan2(double y, double x) {
        return Math.atan2(y, x);
    }

    public static double trunc(double v) {
        return v < 0.0 ? Math.ceil(v) : Math.floor(v);
    }

    public static double frac(double v) {
        return v - trunc(v);
    }

    public static double degToRad(double v) {
        return v * Math.PI / 180.0;
    }

    public static double radToDeg(double v) {
        return v * 180.0 / Math.PI;
    }

    // For negative angles, d should be negative, m & s positive.
    public static double dmsToRad(double d, double m, double s) {
        if (d >= 0)
            return (d + m / 60 + s / 3600) * Math.PI / 180.0;
        return (d - m / 60 - s / 3600) * Math.PI / 180.0;
    }

    // For negative angles, d should be negative, m & s positive.
    public static double dmsToDeg(double d, double m, double s) {
        if (d >= 0)
            return (d + m / 60 + s / 3600);
        return (d - m / 60 - s / 3600);
    }

    /**
     * How far out of range an angle may be before the {@code while} loops below are replaced by
     * one {@code floor}. Any positive value fixes the liveness bug; a large-ish one is chosen so
     * that ordinary inputs keep taking the exact same arithmetic path they always did, and only
     * genuinely wild ones change.
     * <p>
     * Worst case after the switch-over the loop still runs, but at most a bounded number of
     * times: {@code 100} for {@link #normalizeLatitude}, {@code 50} for {@link #normalizeAngle}.
     */
    private static final double LOOP_LIMIT = 100.0 * Math.PI;

    /**
     * Reduces a latitude into {@code [-pi/2, pi/2]}.
     * <p>
     * <b>Bounded.</b> This was two unbounded {@code while} loops stepping by &pi;, so
     * {@code 1e18} radians took about {@code 3.2e17} iterations — a hung thread, not an
     * exception, reachable from an untrusted coordinate. Anything past {@link #LOOP_LIMIT} is now
     * brought into range with one {@code floor} first; in-range and mildly out-of-range inputs
     * take the original path and produce bit-identical results, including at the exact multiples
     * of &pi; where the loop form and the {@code floor} form disagree on which end of the
     * interval to land.
     *
     * @param angle the latitude, radians
     * @return the latitude reduced into {@code [-pi/2, pi/2]}
     * @throws InvalidValueException if {@code angle} is infinite or {@code NaN}
     */
    public static double normalizeLatitude(double angle) {
        if (Double.isInfinite(angle) || Double.isNaN(angle))
            throw new InvalidValueException("Infinite latitude");
        if (angle > LOOP_LIMIT || angle < -LOOP_LIMIT)
            angle -= Math.PI * Math.floor((angle + HALFPI) / Math.PI);
        while (angle > ProjectionMath.HALFPI)
            angle -= Math.PI;
        while (angle < -ProjectionMath.HALFPI)
            angle += Math.PI;
        return angle;
//		return Math.IEEEremainder(angle, Math.PI);
    }

    /**
     * Reduces a longitude into {@code (-pi, pi]}. Now exactly {@link #adjlon(double)}.
     * <p>
     * <b>Two behaviour changes, both deliberate and both towards PROJ.</b>
     * <ol>
     * <li><b>It no longer throws on a non-finite argument.</b> It used to raise
     *     {@code InvalidValueException("Infinite longitude")} for {@code NaN}, which is
     *     <em>stricter than PROJ</em>: {@code fwd_prepare} tests {@code HUGE_VAL} and its range
     *     comparisons are all false for {@code NaN}, so upstream lets {@code NaN} through. The
     *     rejection also only ever fired when {@code lon_0 != 0}, which is why the same defect
     *     used to throw for {@code +lon_0=15} and return garbage for {@code +lon_0=0}. Non-finite
     *     input is now rejected once, at the entry to the projection, by
     *     {@code Projection.checkForwardDomain}, where it can be attributed to
     *     {@link ErrorCause#INVALID_COORDINATE} instead of masquerading as a bad
     *     <em>parameter</em>.</li>
     * <li><b>It is bounded, and it has PROJ's overshoot window.</b> See
     *     {@link #adjlon(double)}.</li>
     * </ol>
     *
     * @param angle the longitude, radians
     * @return the longitude reduced into {@code (-pi, pi]}, or {@code NaN} if {@code angle} was
     *         not finite
     */
    public static double normalizeLongitude(double angle) {
        return adjlon(angle);
    }

    /**
     * PROJ's {@code adjlon} ({@code 9.8.1:src/adjlon.cpp:6-20}), ported verbatim: reduce a
     * longitude to &plusmn;&pi; with one {@code floor}.
     * <p>
     * Two properties are load bearing and neither is obvious:
     * <ul>
     * <li><b>The {@code 1e-12} overshoot window.</b> Upstream returns the argument untouched
     *     while {@code |lon| < pi + 1e-12}, with the comment "let longitude slightly overshoot,
     *     to avoid spurious sign switching at the date line". The old loop wrapped as soon as
     *     {@code lon > pi}, so a longitude a picoradian past the antimeridian flipped to the
     *     other side of the world. Real corpus points sit inside that band.</li>
     * <li><b>It is {@code NaN}-transparent.</b> {@code Math.abs(NaN) < pi + 1e-12} is
     *     {@code false}, so {@code NaN} falls through the arithmetic and comes out {@code NaN},
     *     exactly as the C does. An infinite argument comes out {@code NaN}, also exactly as the
     *     C does ({@code inf - inf}).</li>
     * </ul>
     *
     * @param longitude the longitude, radians
     * @return the longitude reduced to &plusmn;&pi;
     * @since 1.5.0
     */
    public static double adjlon(double longitude) {
        /* Let longitude slightly overshoot, to avoid spurious sign switching at the date line */
        if (Math.abs(longitude) < Math.PI + 1e-12)
            return longitude;

        /* adjust to 0..2pi range */
        longitude += Math.PI;

        /* remove integral # of 'revolutions' */
        longitude -= TWOPI * Math.floor(longitude / TWOPI);

        /* adjust back to -pi..pi range */
        longitude -= Math.PI;

        return longitude;
    }

    /**
     * Reduces an angle into {@code [0, 2*pi]}.
     * <p>
     * <b>Bounded</b>, for the same reason as {@link #normalizeLatitude(double)}: this was an
     * unbounded {@code while} stepping by 2&pi;.
     *
     * @param angle the angle, radians
     * @return the angle reduced into {@code [0, 2*pi]}
     * @throws InvalidValueException if {@code angle} is infinite or {@code NaN}
     */
    public static double normalizeAngle(double angle) {
        if (Double.isInfinite(angle) || Double.isNaN(angle))
            throw new InvalidValueException("Infinite angle");
        if (angle > LOOP_LIMIT || angle < -LOOP_LIMIT)
            angle -= TWOPI * Math.floor(angle / TWOPI);
        while (angle > TWOPI)
            angle -= TWOPI;
        while (angle < 0)
            angle += TWOPI;
        return angle;
    }

/*
	public static void latLongToXYZ(Point2D.Double ll, Point3D xyz) {
		double c = Math.cos(ll.y);
		xyz.x = c * Math.cos(ll.x);
		xyz.y = c * Math.sin(ll.x);
		xyz.z = Math.sin(ll.y);
	}

	public static void xyzToLatLong(Point3D xyz, Point2D.Double ll) {
		ll.y = MapMath.asin(xyz.z);
		ll.x = MapMath.atan2(xyz.y, xyz.x);
	}
*/

    public static double greatCircleDistance(double lon1, double lat1, double lon2, double lat2) {
        double dlat = Math.sin((lat2 - lat1) / 2);
        double dlon = Math.sin((lon2 - lon1) / 2);
        double r = Math.sqrt(dlat * dlat + Math.cos(lat1) * Math.cos(lat2) * dlon * dlon);
        return 2.0 * Math.asin(r);
    }

    public static double sphericalAzimuth(double lat0, double lon0, double lat, double lon) {
        double diff = lon - lon0;
        double coslat = Math.cos(lat);

        return Math.atan2(
                coslat * Math.sin(diff),
                (Math.cos(lat0) * Math.sin(lat) -
                        Math.sin(lat0) * coslat * Math.cos(diff))
        );
    }

    public static boolean sameSigns(double a, double b) {
        return a < 0 == b < 0;
    }

    public static boolean sameSigns(int a, int b) {
        return a < 0 == b < 0;
    }

    public static double takeSign(double a, double b) {
        a = Math.abs(a);
        if (b < 0)
            return -a;
        return a;
    }

    public static int takeSign(int a, int b) {
        a = Math.abs(a);
        if (b < 0)
            return -a;
        return a;
    }
/*
  public static double distance(Point2D.Double a, Point2D.Double b) {
    return distance(a.x-b.x, a.y-b.y);
  }

	public final static int DONT_INTERSECT = 0;
	public final static int DO_INTERSECT = 1;
	public final static int COLLINEAR = 2;

	public static int intersectSegments(Point2D.Double aStart, Point2D.Double aEnd, Point2D.Double bStart, Point2D.Double bEnd, Point2D.Double p) {
		double a1, a2, b1, b2, c1, c2;
		double r1, r2, r3, r4;
		double denom, offset, num;

		a1 = aEnd.y-aStart.y;
		b1 = aStart.x-aEnd.x;
		c1 = aEnd.x*aStart.y - aStart.x*aEnd.y;
		r3 = a1*bStart.x + b1*bStart.y + c1;
		r4 = a1*bEnd.x + b1*bEnd.y + c1;

		if (r3 != 0 && r4 != 0 && sameSigns(r3, r4))
			return DONT_INTERSECT;

		a2 = bEnd.y-bStart.y;
		b2 = bStart.x-bEnd.x;
		c2 = bEnd.x*bStart.y-bStart.x*bEnd.y;
		r1 = a2*aStart.x + b2*aStart.y + c2;
		r2 = a2*aEnd.x + b2*aEnd.y + c2;

		if (r1 != 0 && r2 != 0 && sameSigns(r1, r2))
			return DONT_INTERSECT;

		denom = a1*b2 - a2*b1;
		if (denom == 0)
			return COLLINEAR;

		offset = denom < 0 ? -denom/2 : denom/2;

		num = b1*c2 - b2*c1;
		p.x = (num < 0 ? num-offset : num+offset) / denom;

		num = a2*c1 - a1*c2;
		p.y = (num < 0 ? num-offset : num+offset) / denom;

		return DO_INTERSECT;
	}

  /*
	public static double dot(Point2D.Double a, Point2D.Double b) {
		return a.x*b.x + a.y*b.y;
	}
	
	public static Point2D.Double perpendicular(Point2D.Double a) {
		return new Point2D.Double(-a.y, a.x);
	}
	
	public static Point2D.Double add(Point2D.Double a, Point2D.Double b) {
		return new Point2D.Double(a.x+b.x, a.y+b.y);
	}
	
	public static Point2D.Double subtract(Point2D.Double a, Point2D.Double b) {
		return new Point2D.Double(a.x-b.x, a.y-b.y);
	}
	
	public static Point2D.Double multiply(Point2D.Double a, Point2D.Double b) {
		return new Point2D.Double(a.x*b.x, a.y*b.y);
	}
	
	public static double cross(Point2D.Double a, Point2D.Double b) {
		return a.x*b.y - b.x*a.y;
	}
  
  public static void normalize(Point2D.Double a) {
    double d = distance(a.x, a.y);
    a.x /= d;
    a.y /= d;
  }
  
  public static void negate(Point2D.Double a) {
    a.x = -a.x;
    a.y = -a.y;
  }
  

*/

    public static double cross(double x1, double y1, double x2, double y2) {
        return x1 * y2 - x2 * y1;
    }

    public static double longitudeDistance(double l1, double l2) {
        return Math.min(
                Math.abs(l1 - l2),
                ((l1 < 0) ? l1 + Math.PI : Math.PI - l1) + ((l2 < 0) ? l2 + Math.PI : Math.PI - l2)
        );
    }

    public static double geocentricLatitude(double lat, double flatness) {
        double f = 1.0 - flatness;
        return Math.atan((f * f) * Math.tan(lat));
    }

    public static double geographicLatitude(double lat, double flatness) {
        double f = 1.0 - flatness;
        return Math.atan(Math.tan(lat) / (f * f));
    }

    /**
     * PROJ 4's {@code pj_tsfn}: {@code ts = exp(-psi)} via {@code tan} and {@code pow}.
     *
     * @param phi    the geographic latitude, radians
     * @param sinphi {@code sin(phi)}
     * @param e      the first eccentricity
     * @return {@code exp(-psi)}
     * @deprecated superseded by {@link ConformalLat#tsfn(double, double, double)}, a port
     *             of PROJ 9.8.1's {@code src/tsfn.cpp}. This version returns
     *             {@code 0.9999999999999999} at {@code phi = 0} where the correct answer
     *             is exactly {@code 1.0}, and loses relative accuracy near the poles.
     *             Retained only because it is public API.
     */
    @Deprecated
    public static double tsfn(double phi, double sinphi, double e) {
        sinphi *= e;
        return (Math.tan(.5 * (ProjectionMath.HALFPI - phi)) /
                Math.pow((1. - sinphi) / (1. + sinphi), .5 * e));
    }

    public static double msfn(double sinphi, double cosphi, double es) {
        return cosphi / Math.sqrt(1.0 - es * sinphi * sinphi);
    }

    private final static int N_ITER = 15;

    /**
     * PROJ 4's {@code pj_phi2}: a 15-step Newton iteration with a {@code pow} call on
     * every trip.
     *
     * @param ts {@code exp(-psi)}
     * @param e  the first eccentricity
     * @return the geographic latitude, radians
     * @deprecated superseded by {@link ConformalLat#phi2(double, double)}, a port of PROJ
     *             9.8.1's {@code src/phi2.cpp}. Measured on GRS80 this version is up to
     *             4,145 nm from the truth against a 50 nm conformance bar, and its
     *             data-dependent trip count makes the answer platform-dependent.
     *             Retained only because it is public API.
     */
    @Deprecated
    public static double phi2(double ts, double e) {
        double eccnth, phi, con, dphi;
        int i;

        eccnth = .5 * e;
        phi = ProjectionMath.HALFPI - 2. * Math.atan(ts);
        i = N_ITER;
        do {
            con = e * Math.sin(phi);
            dphi = ProjectionMath.HALFPI - 2. * Math.atan(ts * Math.pow((1. - con) / (1. + con), eccnth)) - phi;
            phi += dphi;
        } while (Math.abs(dphi) > 1e-10 && --i != 0);
        if (i <= 0)
            throw new ConvergenceFailureException("Computation of phi2 failed to converage after " + N_ITER + " iterations");
        return phi;
    }

    private final static double C00 = 1.0;
    private final static double C02 = .25;
    private final static double C04 = .046875;
    private final static double C06 = .01953125;
    private final static double C08 = .01068115234375;
    private final static double C22 = .75;
    private final static double C44 = .46875;
    private final static double C46 = .01302083333333333333;
    private final static double C48 = .00712076822916666666;
    private final static double C66 = .36458333333333333333;
    private final static double C68 = .00569661458333333333;
    private final static double C88 = .3076171875;
    private final static int MAX_ITER = 10;

    /**
     * PROJ 4's {@code pj_enfn}: the five meridional-distance coefficients as a series in
     * {@code es}.
     *
     * @param es the squared first eccentricity
     * @return the coefficient array consumed by {@link #mlfn} and {@link #inv_mlfn}
     * @deprecated superseded by {@link MeridianArc}, a port of PROJ 9.8.1's
     *             {@code src/mlfn.cpp}, whose series is 6th order in the <em>third
     *             flattening</em> rather than in {@code es}. The three functions
     *             {@code enfn}/{@code mlfn}/{@code inv_mlfn} form one series family and
     *             must be replaced as a set. Retained only because it is public API.
     */
    @Deprecated
    public static double[] enfn(double es) {
        double t;
        double[] en = new double[5];
        en[0] = C00 - es * (C02 + es * (C04 + es * (C06 + es * C08)));
        en[1] = es * (C22 - es * (C04 + es * (C06 + es * C08)));
        en[2] = (t = es * es) * (C44 - es * (C46 + es * C48));
        en[3] = (t *= es) * (C66 - es * C68);
        en[4] = t * es * C88;
        return en;
    }

    /**
     * PROJ 4's {@code pj_mlfn}: meridional distance from the equator, in units of the
     * semi-major axis.
     *
     * @param phi  the geographic latitude, radians
     * @param sphi {@code sin(phi)}
     * @param cphi {@code cos(phi)}
     * @param en   coefficients from {@link #enfn}
     * @return the meridional arc length, divided by the semi-major axis
     * @deprecated superseded by {@link MeridianArc#mlfn(double, double, double)}. Measured
     *             on GRS80 this version is up to 4,920 nm from the truth against a 50 nm
     *             conformance bar. Retained only because it is public API.
     */
    @Deprecated
    public static double mlfn(double phi, double sphi, double cphi, double[] en) {
        cphi *= sphi;
        sphi *= sphi;
        return en[0] * phi - cphi * (en[1] + sphi * (en[2] + sphi * (en[3] + sphi * en[4])));
    }

    /**
     * PROJ 4's {@code pj_inv_mlfn}: geographic latitude from meridional distance, by up to
     * ten Newton steps against {@link #mlfn} itself.
     *
     * @param arg the meridional arc length, divided by the semi-major axis
     * @param es  the squared first eccentricity
     * @param en  coefficients from {@link #enfn}
     * @return the geographic latitude, radians
     * @throws ConvergenceFailureException if the Newton iteration has not reached
     *         {@code 1e-11} after {@code MAX_ITER = 10} steps
     * @deprecated superseded by {@link MeridianArc#invMlfn(double)}, which is
     *             <b>closed form</b> — no iteration at all. Because
     *             it is Newton's method run against {@link #mlfn} it is self-consistent by
     *             construction: its round trip closes to 0.7 nm while both halves sit
     *             4,920 nm from the truth. Retained only because it is public API.
     */
    @Deprecated
    public static double inv_mlfn(double arg, double es, double[] en) {
        double s, t, phi, k = 1. / (1. - es);

        phi = arg;
        for (int i = MAX_ITER; i != 0; i--) {
            s = Math.sin(phi);
            t = 1. - es * s * s;
            phi -= t = (mlfn(phi, s, Math.cos(phi), en) - arg) * (t * Math.sqrt(t)) * k;
            // Written inverted -- !(|t| >= tol) rather than |t| < tol -- so that a NaN takes the
            // *return* branch rather than the throw below. That is upstream's own idiom
            // (`!(fabs(dtau) >= stol)` in tmerc.cpp, `!(fabs(tau) < TMAX)` in others) and it is
            // load bearing here: NaN in must be NaN out, as a result. A NaN correction means the
            // caller supplied a NaN, not that the iteration failed on real input.
            if (!(Math.abs(t) >= 1e-11))
                return phi;
        }
        // 1.4.3 fell out of the loop with a bare `return phi;` and no convergence test of any
        // kind -- the single most widely shared instance of the defect, because tmerc, Bonne,
        // EquidistantAzimuthal and Cassini all inverse-project through here. The unconverged
        // iterate is a finite, in-range latitude that is wrong by an unbounded amount.
        // ProjectionException.ERR_17 is PROJ 4's own text for this condition.
        throw new ConvergenceFailureException(ProjectionException.ERR_17
                + ": inv_mlfn(" + arg + ", es=" + es + ") did not reach 1e-11 in "
                + MAX_ITER + " Newton steps (last iterate " + phi + ")");
    }

    private final static double P00 = .33333333333333333333;
    private final static double P01 = .17222222222222222222;
    private final static double P02 = .10257936507936507936;
    private final static double P10 = .06388888888888888888;
    private final static double P11 = .06640211640211640211;
    private final static double P20 = .01641501294219154443;

    /**
     * PROJ 4's {@code pj_authset}: the three coefficients of the third-order authalic
     * latitude series.
     *
     * @param es the squared first eccentricity
     * @return the coefficient array consumed by {@link #authlat}
     * @deprecated superseded by {@link AuthalicLat}, a port of PROJ 9.8.1's
     *             {@code src/latitudes.cpp}, whose series is 6th order. Retained only
     *             because it is public API.
     */
    @Deprecated
    public static double[] authset(double es) {
        double t;
        double[] APA = new double[3];
        APA[0] = es * P00;
        t = es * es;
        APA[0] += t * P01;
        APA[1] = t * P10;
        t *= es;
        APA[0] += t * P02;
        APA[1] += t * P11;
        APA[2] = t * P20;
        return APA;
    }

    /**
     * PROJ 4's {@code pj_authlat}: geographic latitude from authalic latitude, by a
     * third-order Fourier series.
     *
     * @param beta the authalic latitude, radians
     * @param APA  coefficients from {@link #authset}
     * @return the geographic latitude, radians
     * @deprecated superseded by {@link AuthalicLat#inverse(double)}. Measured on GRS80 this
     *             version is <b>2.21 mm</b> off at latitude 18.01 degrees against the
     *             0.1 mm bar that {@code aea}'s inverse conformance assertions set — 22
     *             times the bar. Retained only because it is public API.
     */
    @Deprecated
    public static double authlat(double beta, double[] APA) {
        double t = beta + beta;
        return (beta + APA[0] * Math.sin(t) + APA[1] * Math.sin(t + t) + APA[2] * Math.sin(t + t + t));
    }

    /**
     * PROJ 4's {@code pj_qsfn}: the authalic quantity {@code q}, Snyder (3-12).
     *
     * @param sinphi {@code sin(phi)}
     * @param e      the first eccentricity
     * @param one_es {@code 1 - es}
     * @return {@code q}
     * @deprecated superseded by {@link AuthalicLat#q(double)}. This version writes Snyder's
     *             second term as {@code 0.5 log((1-x)/(1+x))/e}, which cancels near
     *             {@code phi = 0}; the replacement uses {@code -atanh(x)/e}, which does
     *             not. Retained only because it is public API.
     */
    @Deprecated
    public static double qsfn(double sinphi, double e, double one_es) {
        double con;

        if (e >= 1.0e-7) {
            con = e * sinphi;
            return (one_es * (sinphi / (1. - con * con) -
                    (.5 / e) * Math.log((1. - con) / (1. + con))));
        } else
            return (sinphi + sinphi);
    }

    /*
     * Java translation of "Nice Numbers for Graph Labels"
     * by Paul Heckbert
     * from "Graphics Gems", Academic Press, 1990
     */
    public static double niceNumber(double x, boolean round) {
        int expv;				/* exponent of x */
        double f;				/* fractional part of x */
        double nf;				/* nice, rounded fraction */

        expv = (int) Math.floor(Math.log(x) / Math.log(10));
        f = x / Math.pow(10., expv);		/* between 1 and 10 */
        if (round) {
            if (f < 1.5)
                nf = 1.;
            else if (f < 3.)
                nf = 2.;
            else if (f < 7.)
                nf = 5.;
            else
                nf = 10.;
        } else if (f <= 1.)
            nf = 1.;
        else if (f <= 2.)
            nf = 2.;
        else if (f <= 5.)
            nf = 5.;
        else
            nf = 10.;
        return nf * Math.pow(10., expv);
    }

    /**
     * Evaluate complex polynomial.
     * Note coefficients are always C[1] to C[n], C[0] is always (0,0).
     */
    public static Complex zpoly1(Complex z, Complex[] c) {
        Complex a = new Complex(c[c.length - 1]);
        double t;
        int n = c.length - 1;
        while (n-- > 0) {
            Complex C = c[n];
            a.r = C.r + z.r * (t = a.r) - z.i * a.i;
            a.i = C.i + z.r * a.i + z.i * t;
        }
        a.r = z.r * (t = a.r) - z.i * a.i;
        a.i = z.r * a.i + z.i * t;
        return a;
    }

    /**
     * Evaluate a complex polynomial and its derivative
     */
    public static Complex zpoly1d(Complex z, Complex[] C, Complex der) {
        Complex a, b;
        double t;
        boolean first = true;

        a = new Complex(C[C.length - 1]);
        b = new Complex(a);
        for (int i = C.length - 1; i > 0; i--) {
            if (first) {
                first = false;
            } else {
                b.r = a.r + z.r * (t = b.r) - z.i * b.i;
                b.i = a.i + z.r * b.i + z.i * t;
            }
            Complex c = C[i-1];
            a.r = c.r + z.r * (t = a.r) - z.i * a.i;
            a.i = c.i + z.r * a.i + z.i * t;
        }

        b.r = a.r + z.r * (t = b.r) - z.i * b.i;
        b.i = a.i + z.r * b.i + z.i * t;
        a.r = z.r * (t = a.r) - z.i * a.i;
        a.i = z.r * a.i + z.i * t;
        der.i = b.i;
        der.r = b.r;
        return a;
    }

     /**
     * Tests whether the datum parameter-based transform
     * is the identity transform
     * (in which case datum transformation can be short-circuited,
     * thus avoiding some loss of numerical precision).
     *
     * @param transform
     * @return
     */
    public static boolean isIdentity(double[] transform) {
        for (int i = 0; i < transform.length; i++) {
            // scale factor will normally be 1 for an identity transform
            if (i == 6) {
                if (transform[i] != 1.0 && transform[i] != 0.0)
                    return false;
            } else if (transform[i] != 0.0) return false;
        }
        return true;
    }


    /* SECONDS_TO_RAD = Pi/180/3600 */
    public static final double SECONDS_TO_RAD = 4.84813681109535993589914102357e-6;
    public static final double MILLION = 1000000.0;
}

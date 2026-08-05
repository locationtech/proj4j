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

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.*;
import org.locationtech.proj4j.util.GenericInverse2D;
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Cassini-Soldner, {@code 9.8.1:src/projections/cass.cpp}.
 *
 * <p>Three changes, all of them upstream's.
 *
 * <p><b>1. The meridian arc.</b> {@code ProjectionMath.enfn}/{@code mlfn}/{@code inv_mlfn} are
 * replaced by {@link MeridianArc}, the port of {@code 9.8.1:src/mlfn.cpp} — a 6th-order series in
 * the third flattening, with a <b>closed-form</b> inverse. Measured at longitude 0, where the
 * northing <em>is</em> the meridian arc, the forward went from 4.92 um out at latitude 72.55
 * degrees to bit-exact against an independent 64-panel Gauss-Legendre quadrature, and the inverse
 * from 4.91 um to 3.2 nm.
 *
 * <p><b>2. The sign of the {@code A^4} easting term.</b> See the comment in {@link #project}: this
 * is upstream's {@code 78d89828}, "cass: fix forward computation of easting (fixes #3432)", which
 * proj4j predates. It is the dominant part of the forward/inverse mismatch.
 *
 * <p><b>3. The inverse is refined to a true inverse.</b> {@code cass.cpp:75-82} finishes the
 * truncated-series inverse with {@code pj_generic_inverse_2d} at a {@code 1e-12} residual, and
 * upstream's comment says why: it "enables to make the 5108.gie roundtripping tests to success,
 * with at most 2 iterations". proj4j stopped at the series, which is truncated at {@code D^4} and
 * therefore is not the inverse of {@link #project} however accurate each half is on its own. That
 * is why {@code gigs/5108} passed all 32 of its point checks while 14 of its 17
 * {@code roundtrip 1000} blocks at a 6 mm bar failed.
 *
 * <p>Measured on {@code gigs/5108}'s own operation, with the sign already fixed so that only the
 * refinement is in question: <b>3 of 16 round trips pass without it, 16 of 16 with it</b>, the worst
 * residual falling from 37.9 m (at longitude 109, 5.6 degrees off the central meridian) to
 * 0.06 mm. Against the pre-change code as a whole — old sign, old series, no refinement — the same
 * sixteen rows also passed 3 of 16, worst 67.4 m. The refinement works precisely because it inverts
 * {@link #project} itself, so it cannot drift from the forward by construction, which is the
 * property {@code roundtrip} asserts.
 */
public class CassiniProjection extends Projection {

	private static final long serialVersionUID = -582473684884518454L;

	/*
	 * Only initialize()-computed configuration may live in a field. Every quantity that is
	 * derived from the coordinate being transformed is a local: project()/projectInverse() are
	 * called concurrently through a shared CoordinateTransform, and per-call scratch held in a
	 * field interleaves between threads into a finite, plausible, wrong coordinate.
	 *
	 * The GenericInverse2D.Forward2D below is a stateless method reference into project(), and the
	 * two scratch ProjCoordinates GenericInverse2D uses are allocated inside solve(), on the
	 * calling thread's stack. Nothing here is shared mutable state.
	 */

	/** Meridional distance from the equator to the latitude of origin. Set by {@link #initialize()}. */
	private double m0;

	/**
	 * The 6th-order meridian-arc series. Built once per CRS in {@link #initialize()} — its
	 * coefficient tables depend only on the ellipsoid, and precomputing them is the whole point of
	 * holding it rather than deriving it per call. Immutable, thread safe and
	 * {@link java.io.Serializable}, as {@link Projection} requires.
	 */
	private MeridianArc meridian;

	/**
	 * {@code Q->hyperbolic}, {@code +hyperbolic} ({@code cass.cpp:127}): the Vanua Levu variant,
	 * which subtracts a cubic correction from the northing.
	 *
	 * <p><b>Read upstream with {@code pj_param_exists}, not with the {@code b} sigil</b> — so
	 * presence is the whole test and {@code +hyperbolic=f} is <em>true</em>. That is why
	 * {@code Proj4Parser} dispatches it with {@code containsKey} rather than {@code parseBoolean}.
	 *
	 * <p>Also why the inverse needs no change: {@code cass_e_inverse} finishes with
	 * {@code pj_generic_inverse_2d} <em>unconditionally</em> ({@code cass.cpp:81}), seeded from
	 * the non-hyperbolic series, so Newton on the hyperbolic {@link #project} converges to the
	 * hyperbolic inverse by construction. {@link #projectInverse} already does exactly that.
	 */
	private boolean hyperbolic;

	private final static double EPS10 = 1e-10;
	private final static double C1 = .16666666666666666666;
	private final static double C2 = .00833333333333333333;
	private final static double C3 = .04166666666666666666;
	private final static double C4 = .33333333333333333333;
	private final static double C5 = .06666666666666666666;

	/**
	 * The residual at which {@code cass.cpp:81} stops refining. Upstream's constant, and it is
	 * reached in at most two iterations for any point the projection is used at.
	 */
	private final static double DELTA_XY_TOLERANCE = 1e-12;

	public CassiniProjection() {
		projectionLatitude = ProjectionMath.toRad(0);
		projectionLongitude = ProjectionMath.toRad(0);
		minLongitude = ProjectionMath.toRad(-90);
		maxLongitude = ProjectionMath.toRad(90);
		initialize();
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		if (spherical) {
			xy.x = Math.asin(Math.cos(lpphi) * Math.sin(lplam));
			xy.y = Math.atan2(Math.tan(lpphi) , Math.cos(lplam)) - projectionLatitude;
		} else {
			double n = Math.sin(lpphi);
			double c = Math.cos(lpphi);
			xy.y = meridian.mlfn(lpphi, n, c);
			// cass.cpp:31-32. nu_square is kept because the hyperbolic correction below needs
			// rho = nu_square * (1 - es) * nu, and recomputing it from nu would cost a second
			// square root for no gain.
			final double nuSquare = 1./(1. - es * n * n);
			n = Math.sqrt(nuSquare);
			double tn = Math.tan(lpphi); double t = tn * tn;
			double a1 = lplam * c;
			c *= es * c / (1 - es);
			double a2 = a1 * a1;
			// cass.cpp:41, Snyder (1987) Eq. 13-1 and EPSG Guidance Note 7-2:
			//   x = nu * A * (1 - A^2 T (1/6 + (8 - T + 8C) A^2 / 120))
			// proj4j had `C1 - (...)`, which is what PROJ itself had until upstream commit
			// 78d89828, "cass: fix forward computation of easting (fixes #3432)". The mis-signed
			// term is O(A^4) inside a bracket multiplied by A, so the absolute easting error grows
			// like lam^5 -- 31 mm at 2.8 degrees from the central meridian for the +ellps=airy
			// case at builtins.gie:931.
			//
			// The discriminating assertion is builtins.gie:911, the EPSG Guidance Note 7-2 test
			// point, whose expected 66644.94040882 links is an external value printed to eight
			// decimals: `+` reproduces it exactly and `-` misses it by 2.6e-5 links. Note that
			// builtins.gie:859's 222605.285776991 for +ellps=GRS80 at (2, 1) is a *stale*
			// expectation from before 78d89828 -- it matches `-` to the last digit and `+` to
			// 13.5 um -- and survives only because that block's bar is 0.1 mm. Do not use it to
			// choose the sign.
			xy.x = n * a1 * (1. - a2 * t *
				(C1 + (8. - t + 8. * c) * a2 * C2));
			xy.y -= m0 - n * tn * a2 *
				(.5 + (5. - t + 6. * c) * a2 * C3);
			if (hyperbolic) {
				// cass.cpp:42-45, EPSG Guidance Note 7-2's "Hyperbolic Cassini-Soldner"
				// (EPSG method 9833), used by Vanua Levu 1915 / Vanua Levu Grid. Applied to
				// the FINISHED northing, cubed - so it must come after the M - m0 + ... line
				// above and not be folded into it.
				final double rho = nuSquare * (1. - es) * n;
				xy.y -= xy.y * xy.y * xy.y / (6. * rho * n);
			}
		}
		return xy;
	}

	/**
	 * {@code +hyperbolic}: the Vanua Levu variant of the ellipsoidal forward.
	 *
	 * <p>No effect on a declared sphere, matching {@code PJ_PROJECTION(cass)}, which returns
	 * before it reads the flag when {@code es == 0}.
	 *
	 * <p>Setting this after {@link #initialize()} is safe and needs no re-initialisation:
	 * nothing {@code initialize()} computes depends on it, and it is read per coordinate.
	 *
	 * @param hyperbolic whether to apply the cubic northing correction
	 * @since 1.5.0
	 */
	public void setHyperbolic(boolean hyperbolic) {
		this.hyperbolic = hyperbolic;
	}

	/**
	 * @return whether the {@code +hyperbolic} variant is in force
	 * @since 1.5.0
	 */
	public boolean isHyperbolic() {
		return hyperbolic;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		if (spherical) {
			double dd = xyy + projectionLatitude;
			out.y = Math.asin(Math.sin(dd) * Math.cos(xyx));
			out.x = Math.atan2(Math.tan(xyx), Math.cos(dd));
		} else {
			double ph1;

			ph1 = meridian.invMlfn(m0 + xyy);
			double tn = Math.tan(ph1); double t = tn * tn;
			double n = Math.sin(ph1);
			double r = 1. / (1. - es * n * n);
			n = Math.sqrt(r);
			r *= (1. - es) * n;
			double dd = xyx / n;
			double d2 = dd * dd;
			out.y = ph1 - (n * tn / r) * d2 *
				(.5 - (1. + 3. * t) * d2 * C3);
			out.x = dd * (1. + t * d2 *
				(-C4 + (1. + 3. * t) * d2 * C5)) / Math.cos(ph1);

			// cass.cpp:75-82. The series above is truncated at D^4, so it is not the inverse of
			// project() -- the mismatch grows like lam^4 and reaches 68 mm at 3 degrees from the
			// central meridian. Newton on project() itself removes it; upstream uses the same
			// seed and the same tolerance, and notes that this is what makes 5108.gie round-trip.
			GenericInverse2D.solve(xyx, xyy, this::project, out.x, out.y,
					DELTA_XY_TOLERANCE, out);
		}
		return out;
	}

	public void initialize() {
		super.initialize();
		if (!spherical) {
			meridian = MeridianArc.fromEs(es);
			m0 = meridian.mlfn(projectionLatitude, Math.sin(projectionLatitude), Math.cos(projectionLatitude));
		}
	}

	public boolean hasInverse() {
		return true;
	}

	/**
	 * Returns the ESPG code for this projection, or 0 if unknown.
	 */
	public int getEPSGCode() {
		return 9806;
	}

	public String toString() {
		return "Cassini";
	}

}

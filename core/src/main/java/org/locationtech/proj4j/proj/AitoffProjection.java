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
 *******************************************************************************/

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import java.util.Objects;

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Aitoff and Winkel Tripel ({@code +proj=aitoff}, {@code +proj=wintri}), ported from
 * {@code 9.8.1:src/projections/aitoff.cpp}. One file, one kernel, two {@code PROJ_HEAD}s — the
 * Winkel Tripel is the Aitoff averaged with an equirectangular at {@code +lat_1}.
 *
 * <h2>There was no inverse at all</h2>
 *
 * <p>{@code hasInverse()} returned {@code false} and {@code projectInverse} was never overridden, so
 * an inverse fell through to {@link Projection}'s <b>identity</b>, ungated. Upstream has had a
 * Newton–Raphson inverse since 2015 (Tutic and Gradiser, after Bildirici and Ipb&uuml;ker's
 * general Jacobian method): two nested loops, an inner 10-trip Newton at {@code EPSILON = 1e-12} and
 * an outer 20-trip re-seed that re-evaluates the forward and repeats while the residual is still
 * above {@code EPSILON}. It is ported whole, including
 *
 * <ul>
 * <li>the {@code fmod(dl, pi)} on the longitude increment, which keeps a step from jumping a whole
 *     revolution;</li>
 * <li>the two <em>reflections</em> that fold a latitude past a pole back inside it — Aitoff is
 *     symmetric about the poles, so Newton can legitimately converge on the mirror solution;</li>
 * <li>{@code lam = 0} at an Aitoff pole, where the longitude is genuinely undefined and upstream
 *     picks zero rather than reporting a failure;</li>
 * <li>the {@code pow(C, 1.5) == 0} guard, and the both-loops-exhausted domain error.</li>
 * </ul>
 *
 * <p>The corpus rows this closes are all {@code direction inverse}. The four {@code aitoff} inverse
 * rows that already passed did so <b>by accident</b>: they sit ~200 m from the origin, where Aitoff
 * is the identity to nine decimals. A round trip at a couple of degrees showed 23.28 m.
 *
 * <h2>{@code +lat_1} was discarded, and 0 is a legal value for it</h2>
 *
 * <p>{@code wintri} reads {@code +lat_1} and takes {@code cosphi1 = cos(lat_1)}, falling back to
 * {@code acos(2/pi) = 0.636619772367581343} (50&deg;28&prime;) only when the key is <em>absent</em>.
 * This class hard-coded the fall-back behind a {@code //FIXME}. The corpus row is
 * {@code +proj=wintri +a=6400000 +lat_1=0}, i.e. {@code cosphi1 = 1.0}, and the forward was out by
 * <b>40,590 m</b>.
 *
 * <p>Because {@code +lat_1=0} is both legal and meaningful, "absent" cannot be inferred from the
 * value — {@link Projection#projectionLatitude1} initialises to {@code 0.0}. Hence
 * {@link #lat1Explicit}, set by the two setter overrides, in the same shape
 * {@code ObliqueMercatorProjection} uses for {@code +alpha} and {@code +lat_1}/{@code +lat_2}.
 * Upstream rejects a {@code +lat_1} whose cosine is exactly 0, and so does this.
 *
 * <p>{@link WinkelTripelProjection}'s constructor used to pass the {@code cosphi1} constant into
 * this class's {@code projectionLatitude} slot, which set {@code lat_0} to 36.47&deg;. That was inert
 * — nothing here reads {@code lat_0} — but it poisoned {@code equals}/{@code hashCode}, so two
 * {@code wintri}s built by different routes compared unequal.
 */
public class AitoffProjection extends PseudoCylindricalProjection {

	private static final long serialVersionUID = 3081757580534141253L;

	protected final static int AITOFF = 0;
	protected final static int WINKEL = 1;

	/** {@code aitoff.cpp}'s {@code EPSILON}, shared by both loops and by the residual test. */
	private static final double EPSILON = 1e-12;

	/** {@code MAXITER}: the inner Newton budget. */
	private static final int MAXITER = 10;

	/** {@code MAXROUND}: the outer re-seed budget. */
	private static final int MAXROUND = 20;

	/** {@code acos(2/pi)}, i.e. 50&deg;28&prime;: {@code wintri}'s {@code +lat_1} fall-back. */
	public static final double DEFAULT_COSPHI1 = 0.636619772367581343;

	private boolean winkel = false;
	private double cosphi1 = 0;

	/**
	 * Whether a caller supplied {@code +lat_1}. {@link Projection#projectionLatitude1} cannot answer
	 * that: it initialises to {@code 0.0} and {@code +lat_1=0} is a legal, meaningful value for
	 * {@code wintri} — it is the one the corpus uses.
	 */
	private boolean lat1Explicit;

	public AitoffProjection() {
		initialize();
	}

	/**
	 * @param type               the mode, {@link #AITOFF} or {@link #WINKEL}
	 * @param projectionLatitude {@code lat_0}, radians. Retained for source compatibility; nothing
	 *                           here reads {@code lat_0}, and this is emphatically <b>not</b>
	 *                           {@code +lat_1} — see {@link #setProjectionLatitude1(double)}.
	 */
	public AitoffProjection(int type, double projectionLatitude) {
		this.projectionLatitude = projectionLatitude;
		winkel = type == WINKEL;
		initialize();
	}

	/** {@code aitoff_s_forward}. */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double c = 0.5 * lplam;
		double d = Math.acos(FastStrictTrig.cos(lpphi) * FastStrictTrig.cos(c));

		if (d != 0) {
			out.x = 2. * d * FastStrictTrig.cos(lpphi) * FastStrictTrig.sin(c)
					* (out.y = 1. / FastStrictTrig.sin(d));
			out.y *= d * FastStrictTrig.sin(lpphi);
		} else
			out.x = out.y = 0.0;
		if (winkel) {
			out.x = (out.x + lplam * cosphi1) * 0.5;
			out.y = (out.y + lpphi) * 0.5;
		}
		return out;
	}

	/**
	 * {@code aitoff_s_inverse}: Newton–Raphson on the 2&times;2 Jacobian, with an outer re-seed.
	 *
	 * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} where upstream sets
	 *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}: a degenerate Jacobian, or
	 *         both loops exhausted without reaching {@code 1e-12}
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate lp) {
		int iter = 0;
		int round = 0;
		double D, C, f1, f2, f1p, f1l, f2p, f2l, dp = 0, dl = 0, sl, sp, cp, cl, x, y;

		if ((Math.abs(xyx) < EPSILON) && (Math.abs(xyy) < EPSILON)) {
			lp.y = 0.;
			lp.x = 0.;
			return lp;
		}

		/* initial values for the Newton-Raphson method */
		lp.y = xyy;
		lp.x = xyx;
		do {
			iter = 0;
			do {
				sl = FastStrictTrig.sin(lp.x * 0.5);
				cl = FastStrictTrig.cos(lp.x * 0.5);
				sp = FastStrictTrig.sin(lp.y);
				cp = FastStrictTrig.cos(lp.y);
				D = cp * cl;
				C = 1. - D * D;
				final double denom = Math.pow(C, 1.5);
				if (denom == 0) {
					throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
							"inverse of (" + xyx + ", " + xyy + ") reached a degenerate Jacobian: "
									+ "the iterate sits on the projection centre, where "
									+ "(1 - cos(phi)^2*cos(lam/2)^2)^1.5 is exactly 0");
				}
				D = Math.acos(D) / denom;
				f1 = 2. * D * C * cp * sl;
				f2 = D * C * sp;
				f1p = 2. * (sl * cl * sp * cp / C - D * sp * sl);
				f1l = cp * cp * sl * sl / C + D * cp * cl * sp * sp;
				f2p = sp * sp * cl / C + D * sl * sl * cp;
				f2l = 0.5 * (sp * cp * sl / C - D * sp * cp * cp * sl * cl);
				if (winkel) {
					f1 = 0.5 * (f1 + lp.x * cosphi1);
					f2 = 0.5 * (f2 + lp.y);
					f1p *= 0.5;
					f1l = 0.5 * (f1l + cosphi1);
					f2p = 0.5 * (f2p + 1.);
					f2l *= 0.5;
				}
				f1 -= xyx;
				f2 -= xyy;
				dp = f1p * f2l - f2p * f1l;
				dl = (f2 * f1p - f1 * f2p) / dp;
				dp = (f1 * f2l - f2 * f1l) / dp;
				dl = dl % Math.PI; /* fmod: set to the interval [-pi, pi] */
				lp.y -= dp;
				lp.x -= dl;
			} while ((Math.abs(dp) > EPSILON || Math.abs(dl) > EPSILON) && (iter++ < MAXITER));
			if (lp.y > ProjectionMath.HALFPI)
				lp.y -= 2. * (lp.y - ProjectionMath.HALFPI); /* symmetrical solution for Aitoff */
			if (lp.y < -ProjectionMath.HALFPI)
				lp.y -= 2. * (lp.y + ProjectionMath.HALFPI); /* symmetrical solution for Aitoff */
			if ((Math.abs(Math.abs(lp.y) - ProjectionMath.HALFPI) < EPSILON) && !winkel)
				lp.x = 0.; /* if pole in Aitoff, return a longitude of 0 */

			/* recompute x,y from the solution obtained */
			C = 0.5 * lp.x;
			if ((D = Math.acos(FastStrictTrig.cos(lp.y) * FastStrictTrig.cos(C))) != 0.0) {
				y = 1. / FastStrictTrig.sin(D);
				x = 2. * D * FastStrictTrig.cos(lp.y) * FastStrictTrig.sin(C) * y;
				y *= D * FastStrictTrig.sin(lp.y);
			} else
				x = y = 0.;
			if (winkel) {
				x = (x + lp.x * cosphi1) * 0.5;
				y = (y + lp.y) * 0.5;
			}
			/* if too far from the given x,y, repeat with a better approximation of phi,lam */
		} while (((Math.abs(xyx - x) > EPSILON) || (Math.abs(xyy - y) > EPSILON))
				&& (round++ < MAXROUND));

		if (iter == MAXITER && round == MAXROUND) {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"inverse of (" + xyx + ", " + xyy + ") did not reach " + EPSILON + " within "
							+ MAXITER + " Newton trips over " + MAXROUND + " re-seeds; last "
							+ "increments dphi=" + dp + " dlam=" + dl);
		}
		return lp;
	}

	/**
	 * {@code PJ_PROJECTION(wintri)}'s {@code +lat_1} block, plus {@code pj_aitoff_setup}'s
	 * {@code P->es = 0}.
	 *
	 * @throws InvalidValueException where upstream returns
	 *         {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE} for a {@code +lat_1} at a pole
	 */
	public void initialize() {
		es = 0.;
		e = 0.;
		super.initialize();
		if (winkel) {
			if (lat1Explicit) {
				cosphi1 = FastStrictTrig.cos(projectionLatitude1);
				if (cosphi1 == 0.) {
					throw new InvalidValueException(
							"Invalid value for +lat_1: |lat_1| should be < 90 degrees, but is "
									+ Math.toDegrees(projectionLatitude1));
				}
			} else {
				/* 50d28' or acos(2/pi) */
				cosphi1 = DEFAULT_COSPHI1;
			}
		}
	}

	@Override public void setProjectionLatitude1(double projectionLatitude1) {
		super.setProjectionLatitude1(projectionLatitude1);
		this.lat1Explicit = true;
	}

	@Override public void setProjectionLatitude1Degrees(double projectionLatitude1) {
		super.setProjectionLatitude1Degrees(projectionLatitude1);
		this.lat1Explicit = true;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return winkel ? "Winkel Tripel" : "Aitoff";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof AitoffProjection) {
					AitoffProjection p = (AitoffProjection) that;
					return (winkel == p.winkel && cosphi1 == p.cosphi1 && super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(winkel, cosphi1, super.hashCode());
	}
}

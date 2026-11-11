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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;


public class AitoffProjection extends PseudoCylindricalProjection {

	protected final static int AITOFF = 0;
	protected final static int WINKEL = 1;

	private boolean winkel = false;
	private double cosphi1 = 0;

	public AitoffProjection() {
		// NaN marks "+lat_1 not given", so the Winkel Tripel default can be applied
		projectionLatitude1 = Double.NaN;
	}

	protected AitoffProjection(int type) {
		this();
		winkel = type == WINKEL;
	}

	public AitoffProjection(int type, double projectionLatitude) {
		this(type);
		this.projectionLatitude = projectionLatitude;
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double c = 0.5 * lplam;
		double sinc = Math.sin(c);
		double sinphi = Math.sin(lpphi);
		double cosphi = Math.cos(lpphi);
		// sin(d) from hypot() and d from atan2() keep the relative accuracy near the
		// origin, where acos() of a value close to 1 did not (as in PROJ)
		double sind = Math.hypot(cosphi * sinc, sinphi);
		// d / sin(d), with limit 1 at the origin
		double a = sind == 0. ? 1. : Math.atan2(sind, cosphi * Math.cos(c)) / sind;
		out.x = 2. * cosphi * sinc * a;
		out.y = sinphi * a;
		if (winkel) {
			out.x = (out.x + lplam * cosphi1) * 0.5;
			out.y = (out.y + lpphi) * 0.5;
		}
		return out;
	}

	public void initialize() {
		super.initialize();
		if (winkel) {
			if (!Double.isNaN(projectionLatitude1)) {
				if ((cosphi1 = Math.cos(projectionLatitude1)) == 0.)
					throw new ProjectionException("Invalid value for lat_1: |lat_1| should be < 90");
			} else /* 50d28' or acos(2/pi) */
				cosphi1 = 0.636619772367581343;
		}
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		if (!winkel) {
			// Aitoff: (x / 2, y) points along the great circle from the centre, at angular distance alpha
			double halfX = 0.5 * x;
			double alpha = Math.hypot(halfX, y);
			if (alpha > Math.PI)
				throw new ProjectionException("Point outside the Aitoff projection");
			double k = alpha == 0. ? 1. : Math.sin(alpha) / alpha;
			double cosAlpha = Math.cos(alpha);
			// cos(phi) * sin(lam / 2) = halfX * k, sin(phi) = y * k, cos(phi) * cos(lam / 2) = cos(alpha)
			lp.x = 2. * Math.atan2(halfX * k, cosAlpha);
			lp.y = Math.atan2(y * k, Math.hypot(halfX * k, cosAlpha));
			return lp;
		}
		// Winkel Tripel: Newton-Raphson iteration (Bildirici, 2016), as in PROJ
		if (Math.abs(x) < 1e-50 && Math.abs(y) < 1e-50) {
			// first order inverse, exact to double precision this close to the origin,
			// where the Newton-Raphson terms below would underflow
			lp.y = y;
			lp.x = 2. * x / (1. + cosphi1);
			return lp;
		}
		final int maxIter = 10, maxRound = 20;
		final double epsilon = 1e-12;
		int iter, round = 0;
		double dp, dl;
		ProjCoordinate check = new ProjCoordinate();
		lp.y = y;
		lp.x = x;
		do {
			iter = 0;
			do {
				double sl = Math.sin(lp.x * 0.5);
				double cl = Math.cos(lp.x * 0.5);
				double sp = Math.sin(lp.y);
				double cp = Math.cos(lp.y);
				// 1 - D * D and acos(D), computed so that they stay accurate near the origin
				double c = sp * sp + cp * cp * sl * sl;
				double denom = Math.pow(c, 1.5);
				if (denom == 0.)
					throw new ProjectionException("Winkel Tripel inverse did not converge");
				double d = Math.atan2(Math.sqrt(c), cp * cl) / denom;
				double f1 = 2. * d * c * cp * sl;
				double f2 = d * c * sp;
				double f1p = 2. * (sl * cl * sp * cp / c - d * sp * sl);
				double f1l = cp * cp * sl * sl / c + d * cp * cl * sp * sp;
				double f2p = sp * sp * cl / c + d * sl * sl * cp;
				double f2l = 0.5 * (sp * cp * sl / c - d * sp * cp * cp * sl * cl);
				f1 = 0.5 * (f1 + lp.x * cosphi1) - x;
				f2 = 0.5 * (f2 + lp.y) - y;
				f1p *= 0.5;
				f1l = 0.5 * (f1l + cosphi1);
				f2p = 0.5 * (f2p + 1.);
				f2l *= 0.5;
				double det = f1p * f2l - f2p * f1l;
				dl = (f2 * f1p - f1 * f2p) / det;
				dp = (f1 * f2l - f2 * f1l) / det;
				dl %= Math.PI; // keep the step within [-pi, pi]
				lp.y -= dp;
				lp.x -= dl;
			} while ((Math.abs(dp) > epsilon || Math.abs(dl) > epsilon) && iter++ < maxIter);
			if (lp.y > ProjectionMath.HALFPI)
				lp.y -= 2. * (lp.y - ProjectionMath.HALFPI); // the symmetrical solution
			if (lp.y < -ProjectionMath.HALFPI)
				lp.y -= 2. * (lp.y + ProjectionMath.HALFPI);
			// if too far from the given x, y, repeat from the better approximation
			project(lp.x, lp.y, check);
		} while ((Math.abs(x - check.x) > epsilon || Math.abs(y - check.y) > epsilon) && round++ < maxRound);
		if (Math.abs(x - check.x) > epsilon || Math.abs(y - check.y) > epsilon)
			throw new ProjectionException("Winkel Tripel inverse did not converge");
		return lp;
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
					return (winkel == p.winkel && super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(winkel, super.hashCode());
	}
}

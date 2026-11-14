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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

public class VanDerGrintenProjection extends Projection {

	private final static double TOL = 1.e-10;
	private final static double THIRD = .33333333333333333333;
	private final static double TWO_THRD = .66666666666666666666;
	private final static double C2_27 = .07407407407407407407;
	private final static double PI4_3 = 4.18879020478639098458;
	private final static double PISQ = 9.86960440108935861869;
	private final static double TPISQ = 19.73920880217871723738;
	private final static double HPISQ = 4.93480220054467930934;

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double  al, al2, g, g2, p2;

		p2 = Math.abs(lpphi / ProjectionMath.HALFPI);
		if ((p2 - TOL) > 1.) throw new ProjectionException("F");
		if (p2 > 1.)
			p2 = 1.;
		// Only (practically) exact zeros take the first two shortcuts: within TOL of the
		// equator or of the central meridian they were off by up to TOL * R
		if (Math.abs(lpphi) <= 1e-40) {
			out.x = lplam;
			out.y = 0.;
		} else if (Math.abs(lplam) <= 1e-40 || Math.abs(p2 - 1.) < TOL) {
			out.x = 0.;
			out.y = Math.PI * Math.tan(.5 * Math.asin(p2));
			if (lpphi < 0.) out.y = -out.y;
		} else if (Math.abs(lplam) <= Math.PI) {
			// Same values as (29-1) and (29-2) below (Snyder 1987, p. 241), rearranged so
			// that no step subtracts two large terms: close to the origin A, G and P are all
			// large and the direct formulas lost all the digits (points 1e-7 degrees from
			// the origin even failed)
			al = .5 * Math.abs(Math.PI / lplam - lplam / Math.PI); // A from (29-3)
			al2 = al * al;
			double s = p2;                                // sin(theta)
			double c = Math.sqrt(1. - s * s);             // cos(theta)
			g = c * (1. + c) / (s * (1. + c - s));        // G from (29-4)
			double pp = g * (2. / s - 1.);                // P from (29-5)
			double pp2 = pp * pp;
			g2 = g * g;
			double sqrtK = Math.sqrt(al2 * (pp2 - 2. * g + 1.) + pp2 - g2);
			double xPi = g - pp2 < 0.
				? (pp2 - g2) / (pp * sqrtK - al * (g - pp2))
				: (al * (g - pp2) + pp * sqrtK) / (pp2 + al2);
			double yPi = (al2 * (2. * g - 1.) + g2) / (pp * (al2 + g) + al * sqrtK);
			if (yPi < 0.)
				yPi = 0.;
			out.x = lplam < 0. ? -Math.PI * xPi : Math.PI * xPi;
			out.y = lpphi < 0. ? -Math.PI * yPi : Math.PI * yPi;
		} else {
			al = .5 * Math.abs(Math.PI / lplam - lplam / Math.PI);
			al2 = al * al;
			g = Math.sqrt(1. - p2 * p2);
			g = g / (p2 + g - 1.);
			g2 = g * g;
			p2 = g * (2. / p2 - 1.);
			p2 = p2 * p2;
			out.x = g - p2; g = p2 + al2;
			out.x = Math.PI * (al * out.x + Math.sqrt(al2 * out.x * out.x - g * (g2 - p2))) / g;
			if (lplam < 0.) out.x = -out.x;
			out.y = Math.abs(out.x / Math.PI);
			out.y = 1. - out.y * (out.y + 2. * al);
			if (out.y < -TOL) throw new ProjectionException("F");
			if (out.y < 0.)
				out.y = 0.;
			else
				out.y = Math.sqrt(out.y) * (lpphi < 0. ? -Math.PI : Math.PI);
		}
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double t, c0, c1, c2, c3, al, r2, r, m, d, ay, x2, y2;

		x2 = xyx * xyx;
		if ((ay = Math.abs(xyy)) < TOL) {
			out.y = 0.;
			t = x2 * x2 + TPISQ * (x2 + HPISQ);
			// .5 * (x2 - PISQ + sqrt(t)) / x, without the cancellation of sqrt(t) against
			// PISQ - x2 close to the origin
			out.x = xyx == 0. ? 0.
				: x2 < PISQ ? TPISQ * xyx / (Math.sqrt(t) + PISQ - x2)
				: .5 * (x2 - PISQ + Math.sqrt(t)) / xyx;
			return out;
		}
		y2 = xyy * xyy;
		r = x2 + y2;	r2 = r * r;
		c1 = - Math.PI * ay * (r + PISQ);
		c3 = r2 + ProjectionMath.TWOPI * (ay * r + Math.PI * (y2 + Math.PI * (ay + ProjectionMath.HALFPI)));
		c2 = c1 + PISQ * (r - 3. *  y2);
		c0 = Math.PI * ay;
		c2 /= c3;
		al = c1 / c3 - THIRD * c2 * c2;
		m = 2. * Math.sqrt(-THIRD * al);
		d = C2_27 * c2 * c2 * c2 + (c0 * c0 - THIRD * c2 * c1) / c3;
		if (((t = Math.abs(d = 3. * d / (al * m))) - TOL) <= 1.) {
			d = t > 1. ? (d > 0. ? 0. : Math.PI) : Math.acos(d);
			out.y = Math.PI * (m * Math.cos(d * THIRD + PI4_3) - THIRD * c2);
			if (xyy < 0.) out.y = -out.y;
			t = r2 + TPISQ * (x2 - y2 + HPISQ);
			// .5 * (r - PISQ + sqrt(t)) / x, without the cancellation of sqrt(t) against
			// PISQ - r close to the origin: as t - (PISQ - r)^2 = 4 * PISQ * x2, it is also
			// 2 * PISQ * x / (sqrt(t) + PISQ - r)
			double sqrtT = t <= 0. ? 0. : Math.sqrt(t);
			out.x = xyx == 0. ? 0.
				: r < PISQ ? TPISQ * xyx / (sqrtT + PISQ - r)
				: .5 * (r - PISQ + sqrtT) / xyx;
		} else
			throw new ProjectionException("I");
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "van der Grinten (I)";
	}

}

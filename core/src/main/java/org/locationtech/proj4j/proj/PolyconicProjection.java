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
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;

public class PolyconicProjection extends Projection {

	private static final long serialVersionUID = -7631403866540203355L;

	private double ml0;

	/**
	 * The order-6 meridional-arc series, {@code 9.8.1:src/mlfn.cpp}. Null when
	 * {@code spherical}.
	 */
	private MeridianArc meridian;

	private final static double TOL = 1e-10;
	private final static double CONV = 1e-10;
	private final static int N_ITER = 10;
	private final static int I_ITER = 20;
	private final static double ITOL = 1.e-12;

	public PolyconicProjection() {
		minLatitude = ProjectionMath.degToRad(0);
		maxLatitude = ProjectionMath.degToRad(80);
		minLongitude = ProjectionMath.degToRad(-60);
		maxLongitude = ProjectionMath.degToRad(60);
		initialize();
	}
	
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		if (spherical) {
			double  cot, E;

			if (Math.abs(lpphi) <= TOL) {
				out.x = lplam;
				out.y = ml0;
			} else {
				cot = 1. / Math.tan(lpphi);
				out.x = Math.sin(E = lplam * Math.sin(lpphi)) * cot;
				out.y = lpphi - projectionLatitude + cot * (1. - Math.cos(E));
			}
		} else {
			double  ms, sp, cp;

			if (Math.abs(lpphi) <= TOL) {
				out.x = lplam;
				out.y = -ml0;
			} else {
				sp = Math.sin(lpphi);
				ms = Math.abs(cp = Math.cos(lpphi)) > TOL ? ProjectionMath.msfn(sp, cp, es) / sp : 0.;
				// 9.8.1:poly.cpp:37-40: lam is scaled by sin(phi) *first*, and both the
				// sin and the cos are taken of the scaled value. proj4j read the
				// destination's stale x as the multiplicand (out.x *= sp) and then used
				// the *unscaled* lplam in the cosine. Both were unreachable while
				// initialize() forced the spherical branch.
				final double e = lplam * sp;
				out.x = ms * Math.sin(e);
				out.y = (meridian.mlfn(lpphi, sp, cp) - ml0) + ms * (1. - Math.cos(e));
			}
		}
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double lpphi;
		if (spherical) {
			double B, dphi, tp;
			int i;

			// 9.8.1:poly.cpp:112 mutates xy.y to phi0 + xy.y and uses the *mutated*
			// value both as the seed and in B. proj4j tested the sum but then reverted to
			// the raw northing, so the spherical inverse ignored lat_0 entirely.
			xyy = projectionLatitude + xyy;
			if (Math.abs(xyy) <= TOL) {
				out.x = xyx; out.y = 0.;
			} else {
				lpphi = xyy;
				B = xyx * xyx + xyy * xyy;
				i = N_ITER;
				do {
					tp = Math.tan(lpphi);
					lpphi -= (dphi = (xyy * (lpphi * tp + 1.) - lpphi -
						.5 * ( lpphi * lpphi + B) * tp) /
						((lpphi - xyy) / tp - 1.));
				} while (Math.abs(dphi) > CONV && --i > 0);
				if (i == 0) throw new ProjectionException("I");
				out.x = Math.asin(xyx * Math.tan(lpphi)) / Math.sin(lpphi);
				out.y = lpphi;
			}
		} else {
			xyy += ml0;
			if (Math.abs(xyy) <= TOL) { out.x = xyx; out.y = 0.; }
			else {
				double r, c, sp, cp, s2ph, ml, mlb, mlp, dPhi;
				int i;

				r = xyy * xyy + xyx * xyx;
				for (lpphi = xyy, i = I_ITER; i > 0; --i) {
					sp = Math.sin(lpphi);
					s2ph = sp * ( cp = Math.cos(lpphi));
					if (Math.abs(cp) < ITOL)
						throw new ProjectionException("I");
					c = sp * (mlp = Math.sqrt(1. - es * sp * sp)) / cp;
					ml = meridian.mlfn(lpphi, sp, cp);
					mlb = ml * ml + r;
					// 9.8.1:poly.cpp:88 is one_es / (mlp^3), i.e. (1 - es); proj4j had
					// (1 / es), which for GRS80 is 149 -- off by a factor of 150.
					mlp = one_es / (mlp * mlp * mlp);
					lpphi += ( dPhi =
						( ml + ml + c * mlb - 2. * xyy * (c * ml + 1.) ) / (
						es * s2ph * (mlb - 2. * xyy * ml) / c +
						2.* (xyy - ml) * (c * mlp - 1. / s2ph) - mlp - mlp ));
					if (Math.abs(dPhi) <= ITOL)
						break;
				}
				if (i == 0)
					throw new ProjectionException("I");
				c = Math.sin(lpphi);
				out.x = Math.asin(xyx * Math.tan(lpphi) * Math.sqrt(1. - es * c * c)) / Math.sin(lpphi);
				out.y = lpphi;
			}
		}
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public void initialize() {
		super.initialize();
		// The `spherical = true; //FIXME` that used to sit here ran *after*
		// super.initialize() had computed the real value, so the entire ellipsoidal branch
		// below was dead code -- km-scale errors for every ellipsoidal +proj=poly, which
		// is all US State Plane Polyconic. 9.8.1:poly.cpp:157 selects on P->es != 0.
		if (!spherical) {
			meridian = MeridianArc.fromEs(es);
			ml0 = meridian.mlfn(projectionLatitude, Math.sin(projectionLatitude), Math.cos(projectionLatitude));
		} else {
			ml0 = -projectionLatitude;
		}
	}

	public String toString() {
		return "Polyconic (American)";
	}

}

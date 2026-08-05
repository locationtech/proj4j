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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Eckert IV ({@code +proj=eck4}), ported from {@code 9.8.1:src/projections/eck4.cpp}.
 *
 * <h2>Three divergences from 9.8.1, all measured by {@code builtins.gie}</h2>
 *
 * <ol>
 * <li><b>The forward's exhausted-iteration branch was raised as an error.</b> It is not an error
 *     upstream and the corpus proves it: at {@code +a=6400000}, {@code accept -180 90} expects
 *     {@code -8489602.7403 8489602.7403}, and those are <em>exactly</em>
 *     {@code C_x * pi * a} and {@code C_y * a} — the two values upstream's fall-back assigns.
 *     Eckert IV's Newton iteration genuinely does not converge at the pole (the derivative
 *     {@code 1 + c(c+2) - s^2} tends to 0 there), so upstream substitutes the closed-form limit
 *     and returns it as a success. Throwing lost four assertions; a "better" iteration would lose
 *     them too. Non-negotiable 7.</li>
 * <li><b>The inverse had no pole branch.</b> Upstream tests {@code 1 - |sin(theta)|} against
 *     {@code 1e-12} and, inside that band, takes {@code lam = x/C_x} and {@code phi = ±pi/2}
 *     directly rather than running {@code asin}/{@code cos} at their stationary point. Without it
 *     the inverse of the pole is off by <b>165.7 mm</b> against a 0.1 mm bar.</li>
 * <li><b>The inverse had no longitude-range check.</b> Upstream rejects
 *     {@code |lam| - pi &gt; 1e-10} outright and <em>clamps</em> to {@code ±pi} inside that
 *     tolerance, unless {@code +over} is given. Eight {@code expect failure errno
 *     coord_transfm_outside_projection_domain} rows measured this: they feed easting 0.01 m past
 *     the map edge and Proj4J answered with a longitude just past 180&deg;.</li>
 * </ol>
 *
 * <p>{@code +over} is not a Proj4J parameter, so the {@code !P->over} branch is taken
 * unconditionally; the bridge reports any {@code +over} definition as not implemented rather than
 * executing it, so there is no path on which that assumption is wrong.
 */
public class Eckert4Projection extends Projection {

	private static final long serialVersionUID = 8363954354019329109L;

	/** {@code 2 / sqrt(4*pi + pi*pi)}. */
	private final static double C_x = .42223820031577120149;
	/** {@code 2 * sqrt(pi / (4 + pi))}. */
	private final static double C_y = 1.32650042817700232218;
	/** {@code 1 / C_y}. */
	private final static double RC_y = .75386330736002178205;
	/** {@code 2 + pi/2}. */
	private final static double C_p = 3.57079632679489661922;
	/** {@code 1 / C_p}. */
	private final static double RC_p = .28004957675577868795;
	private final static double EPS = 1e-7;
	private final static int NITER = 6;

	/**
	 * Forward projection, {@code eck4_s_forward}.
	 * <p>
	 * Newton's method for the {@code theta} satisfying
	 * {@code theta + sin(theta)*cos(theta) + 2*sin(theta) == C_p * sin(phi)}, with upstream's
	 * closed-form fall-back when six trips are not enough. See the class javadoc for why the
	 * fall-back is a success and not a failure.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double V, s, c;
		int i;

		final double p = C_p * FastStrictTrig.sin(lpphi);
		V = lpphi * lpphi;
		double theta = lpphi * (0.895168 + V * ( 0.0218849 + V * 0.00826809 ));
		for (i = NITER; i > 0; --i) {
			c = FastStrictTrig.cos(theta);
			s = FastStrictTrig.sin(theta);
			theta -= V = (theta + s * (c + 2.) - p) /
				(1. + c * (c + 2.) - s * s);
			if (Math.abs(V) < EPS)
				break;
		}
		if (i == 0) {
			out.x = C_x * lplam;
			out.y = theta < 0. ? -C_y : C_y;
		} else {
			out.x = C_x * lplam * (1. + FastStrictTrig.cos(theta));
			out.y = C_y * FastStrictTrig.sin(theta);
		}
		return out;
	}

	/**
	 * Inverse projection, {@code eck4_s_inverse}.
	 *
	 * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} where upstream sets
	 *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}: a longitude more than
	 *         {@code 1e-10} rad past the antimeridian, or a northing whose implied
	 *         {@code sin(theta)} is past {@code ONE_TOL}
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		final double sinTheta = xyy * RC_y;
		final double oneMinusAbsSinTheta = 1.0 - Math.abs(sinTheta);
		if (oneMinusAbsSinTheta >= 0.0 && oneMinusAbsSinTheta <= 1e-12) {
			// The pole. asin and cos are both stationary here, so upstream substitutes the limit.
			out.x = xyx / C_x;
			out.y = sinTheta > 0 ? ProjectionMath.HALFPI : -ProjectionMath.HALFPI;
		} else {
			final double theta = ProjectionMath.asinChecked(sinTheta);
			final double cosTheta = FastStrictTrig.cos(theta);
			out.x = xyx / (C_x * (1. + cosTheta));
			out.y = ProjectionMath.asinChecked((theta + sinTheta * (cosTheta + 2.)) * RC_p);
		}
		// eck4.cpp's !P->over branch. +over is not a Proj4J parameter; see the class javadoc.
		final double absLamMinusPi = Math.abs(out.x) - Math.PI;
		if (absLamMinusPi > 0.0) {
			if (absLamMinusPi > 1e-10) {
				throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
						"inverse of (" + xyx + ", " + xyy + ") is at longitude "
								+ Math.toDegrees(out.x) + " deg, " + absLamMinusPi
								+ " rad past the antimeridian; the point is outside the map");
			}
			out.x = out.x > 0 ? Math.PI : -Math.PI;
		}
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isEqualArea() {
     return true;
	}

	public String toString() {
		return "Eckert IV";
	}

}

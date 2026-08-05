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

/*
 * This file was semi-automatically converted from the public-domain USGS PROJ source.
 */
package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

public class McBrydeThomasFlatPolarQuarticProjection extends PseudoCylindricalProjection {

	private static final long serialVersionUID = 642253065229040967L;

	private final static int NITER = 20;
	private final static double EPS = 1e-7;
	private final static double ONETOL = 1.000001;
	private final static double C = 1.70710678118654752440;
	private final static double RC = 0.58578643762690495119;
	private final static double FYC = 1.87475828462269495505;
	private final static double RYC = 0.53340209679417701685;
	private final static double FXC = 0.31245971410378249250;
	private final static double RXC = 3.20041258076506210122;

	/**
	 * Forward projection. Port of PROJ 9.8.1 {@code mbtfpq.cpp}'s {@code mbtfpq_s_forward}.
	 * <p>
	 * <b>Fail-closed</b>, plus a repair the throw depends on. Two defects from the 2006
	 * C&rarr;Java conversion:
	 * <ul>
	 * <li>the Newton iteration subtracted its correction from {@code out.y} — the caller's
	 *     destination ordinate, holding whatever stale value was there — where upstream
	 *     subtracts it from {@code lp.phi}. {@code lpphi} therefore never changed, so
	 *     {@code th1} was the same value on every trip: the loop could not converge, and the two
	 *     lines after it used the raw geographic latitude where the solved parametric latitude
	 *     belongs;</li>
	 * <li>there was no convergence test, so exhausting {@code NITER} produced an ordinary-looking
	 *     coordinate.</li>
	 * </ul>
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double th1 = Double.NaN, c;
		int i;
		final double phi = lpphi;

		c = C * Math.sin(lpphi);
		for (i = NITER; i > 0; --i) {
			lpphi -= th1 = (Math.sin(.5*lpphi) + Math.sin(lpphi) - c) /
				(.5*Math.cos(.5*lpphi)  + Math.cos(lpphi));
			if (Math.abs(th1) < EPS) break;
		}
		if (i == 0) {
			throw new ConvergenceFailureException(this,
					"forward parametric-latitude iteration did not converge to " + EPS
							+ " within " + NITER + " iterations for latitude " + phi
							+ " rad (last correction " + th1 + ")");
		}
		out.x = FXC * lplam * (1.0 + 2. * Math.cos(lpphi)/Math.cos(0.5 * lpphi));
		out.y = FYC * Math.sin(0.5 * lpphi);
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double t = 0;

		double lpphi = RYC * xyy;
		if (Math.abs(lpphi) > 1.) {
			if (Math.abs(lpphi) > ONETOL)	throw new ProjectionException("I");
			else if (lpphi < 0.) { t = -1.; lpphi = -Math.PI; }
			else { t = 1.; lpphi = Math.PI; }
		} else
			lpphi = 2. * Math.asin(t = lpphi);
		out.x = RXC * xyx / (1. + 2. * Math.cos(lpphi)/Math.cos(0.5 * lpphi));
		lpphi = RC * (t + Math.sin(lpphi));
		if (Math.abs(lpphi) > 1.)
			if (Math.abs(lpphi) > ONETOL)
				throw new ProjectionException("I");
			else
				lpphi = lpphi < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		else
			lpphi = Math.asin(lpphi);
		out.y = lpphi;
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isEqualArea() {
     return true;
	}

	public String toString() {
		return "McBryde-Thomas Flat-Polar Quartic";
	}

}

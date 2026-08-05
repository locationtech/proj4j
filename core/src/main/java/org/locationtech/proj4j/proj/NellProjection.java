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

import org.locationtech.proj4j.ConvergenceFailureException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.ProjectionMath;

public class NellProjection extends Projection {

	private static final long serialVersionUID = 1682859829774558016L;

	private final static int MAX_ITER = 10;
	private final static double LOOP_TOL = 1e-7;

	/**
	 * Forward projection. Port of PROJ 9.8.1 {@code nell.cpp}'s {@code nell_s_forward}.
	 * <p>
	 * <b>Fail-closed</b>, plus a repair the throw depends on. Three defects from the 2006
	 * C&rarr;Java conversion, all the same mistranslation of C's mutate-then-read idiom:
	 * <ul>
	 * <li>the initial estimate was written as {@code out.y *= …} — scaling the caller's
	 *     destination ordinate, i.e. whatever stale value it held — where upstream scales
	 *     {@code lp.phi};</li>
	 * <li>the Newton correction was also subtracted from {@code out.y}, so {@code lpphi} never
	 *     changed and the correction was identical on every trip;</li>
	 * <li>there was no convergence test, and the two output lines then used the unsolved
	 *     {@code lpphi} — so {@code nell}'s forward was, in effect, {@code y = phi} with a
	 *     cosine-of-the-wrong-angle easting.</li>
	 * </ul>
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double k, V;
		int i;
		final double phi = lpphi;

		k = 2. * Math.sin(lpphi);
		V = lpphi * lpphi;
		lpphi *= 1.00371 + V * (-0.0935382 + V * -0.011412);
		V = Double.NaN;
		for (i = MAX_ITER; i > 0 ; --i) {
			lpphi -= V = (lpphi + Math.sin(lpphi) - k) /
				(1. + Math.cos(lpphi));
			if (Math.abs(V) < LOOP_TOL)
				break;
		}
		if (i == 0) {
			throw new ConvergenceFailureException(this,
					"forward parametric-latitude iteration did not converge to " + LOOP_TOL
							+ " within " + MAX_ITER + " iterations for latitude " + phi
							+ " rad (last correction " + V + ")");
		}
		out.x = 0.5 * lplam * (1. + Math.cos(lpphi));
		out.y = lpphi;
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double th, s;

		out.x = 2. * xyx / (1. + Math.cos(xyy));
		out.y = ProjectionMath.asin(0.5 * (xyy + Math.sin(xyy)));
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Nell";
	}

}

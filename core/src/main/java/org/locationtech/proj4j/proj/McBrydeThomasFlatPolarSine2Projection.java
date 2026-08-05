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
import org.locationtech.proj4j.util.ProjectionMath;

public class McBrydeThomasFlatPolarSine2Projection extends Projection {

	private static final long serialVersionUID = 3899250934609675619L;

	private final static int MAX_ITER = 10;
	private final static double LOOP_TOL = 1e-7;
	private final static double C1 = 0.45503;
	private final static double C2 = 1.36509;
	private final static double C3 = 1.41546;
	private final static double C_x = 0.22248;
	private final static double C_y = 1.44492;
	private final static double C1_2 = 0.33333333333333333333333333;

	/**
	 * Forward projection. Port of PROJ 9.8.1 {@code mbt_fps.cpp}'s {@code mbt_fps_s_forward}.
	 * <p>
	 * <b>Fail-closed</b>, plus the same repair as {@code mbtfpq}: the iteration subtracted its
	 * correction from {@code out.y} — the caller's destination ordinate — instead of from
	 * {@code lpphi}, so the correction was constant across all {@code MAX_ITER} trips and the
	 * loop could never converge, and there was no convergence test to notice.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double k, V = Double.NaN, t;
		int i;
		final double phi = lpphi;

		k = C3 * Math.sin(lpphi);
		for (i = MAX_ITER; i > 0; i--) {
			t = lpphi / C2;
			lpphi -= V = (C1 * Math.sin(t) + Math.sin(lpphi) - k) /
				(C1_2 * Math.cos(t) + Math.cos(lpphi));
			if (Math.abs(V) < LOOP_TOL)
				break;
		}
		if (i == 0) {
			throw new ConvergenceFailureException(this,
					"forward parametric-latitude iteration did not converge to " + LOOP_TOL
							+ " within " + MAX_ITER + " iterations for latitude " + phi
							+ " rad (last correction " + V + ")");
		}
		t = lpphi / C2;
		out.x = C_x * lplam * (1. + 3. * Math.cos(lpphi)/Math.cos(t) );
		out.y = C_y * Math.sin(t);
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double t, s;

		out.y = C2 * (t = ProjectionMath.asin(xyy / C_y));
		out.x = xyx / (C_x * (1. + 3. * Math.cos(out.y)/Math.cos(t)));
		out.y = ProjectionMath.asin((C1 * Math.sin(t) + Math.sin(out.y)) / C3);
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "McBryde-Thomas Flat-Pole Sine (No. 2)";
	}

}

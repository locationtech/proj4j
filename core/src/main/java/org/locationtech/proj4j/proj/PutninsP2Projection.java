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

public class PutninsP2Projection extends Projection {

	private static final long serialVersionUID = 8722258293075600636L;

	private final static double C_x = 1.89490;
	private final static double C_y = 1.71848;
	private final static double C_p = 0.6141848493043784;
	private final static double EPS = 1e-10;
	private final static int NITER = 10;
	private final static double PI_DIV_3 = 1.0471975511965977;

	/**
	 * Forward projection.
	 * <p>
	 * <b>Fail-closed</b>, plus a repair the throw depends on. PROJ's {@code putp2.cpp} clamps to
	 * {@code ±PI_DIV_3} on non-convergence; Proj4J throws, because that is a specific plausible
	 * latitude a caller cannot distinguish from a converged one.
	 * <p>
	 * The iteration itself was the 2006 C&rarr;Java conversion's mutate-then-read mistranslation,
	 * twice: the initial estimate was written as {@code out.y *= …}, scaling the caller's
	 * destination ordinate — whatever stale value it held — where upstream scales
	 * {@code lp.phi}; and the Newton correction was subtracted from {@code out.y} too, so
	 * {@code lpphi} never moved and {@code V} was identical on all {@code NITER} trips. The two
	 * output lines then used the raw geographic latitude where the solved parametric latitude
	 * belongs. Both now run on {@code lpphi}, and the forward reproduces PROJ 9.8.1 to the
	 * micrometre.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double p, c, s, V = Double.NaN;
		int i;
		final double phi = lpphi;

		p = C_p * Math.sin(lpphi);
		s = lpphi * lpphi;
		lpphi *= 0.615709 + s * ( 0.00909953 + s * 0.0046292 );
		for (i = NITER; i > 0; --i) {
			c = Math.cos(lpphi);
			s = Math.sin(lpphi);
			lpphi -= V = (lpphi + s * (c - 1.) - p) /
				(1. + c * (c - 1.) - s * s);
			if (Math.abs(V) < EPS)
				break;
		}
		if (i == 0) {
			throw new ConvergenceFailureException(this,
					"forward parametric-latitude iteration did not converge to " + EPS
							+ " within " + NITER + " iterations for latitude " + phi
							+ " rad (last correction " + V + ")");
		}
		out.x = C_x * lplam * (Math.cos(lpphi) - 0.5);
		out.y = C_y * Math.sin(lpphi);
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double c;

		out.y = ProjectionMath.asin(xyy / C_y);
		out.x = xyx / (C_x * ((c = Math.cos(out.y)) - 0.5));
		out.y = ProjectionMath.asin((out.y + Math.sin(out.y) * (c - 1.)) / C_p);
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Putnins P2";
	}

}

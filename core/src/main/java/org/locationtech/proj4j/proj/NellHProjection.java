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

public class NellHProjection extends Projection {

	private static final long serialVersionUID = -5588395073299363305L;

	private final static int NITER = 9;
	private final static double EPS = 1e-7;

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		out.x = 0.5 * lplam * (1. + Math.cos(lpphi));
		out.y = 2.0 * (lpphi - Math.tan(0.5 *lpphi));
		return out;
	}

	/**
	 * Inverse projection. Port of PROJ 9.8.1 {@code nell_h.cpp}'s {@code nell_h_s_inverse}.
	 * <p>
	 * <b>Fail-closed</b>, and the iteration itself had to be repaired for that to mean
	 * anything. Two defects, both from the 2006 C&rarr;Java conversion:
	 * <ul>
	 * <li>the Newton iteration substituted the <em>constant</em> {@code xyy} for the running
	 *     estimate everywhere upstream writes {@code lp.phi}, so the correction {@code V} was
	 *     the same value on every trip and the loop could never converge unless it happened to
	 *     satisfy the tolerance immediately;</li>
	 * <li>it accumulated into {@code out.y}, which is the caller's destination coordinate and
	 *     holds whatever stale value the caller left there — {@code BasicCoordinateTransform}
	 *     passes the same object as source and destination, so that was the input northing.</li>
	 * </ul>
	 * The combination meant the {@code i == 0} branch was taken for essentially every input, so
	 * {@code nell_h}'s inverse returned a <b>pole</b> almost unconditionally. Clamping to the
	 * pole is now a throw, and the iteration is upstream's, over a local initialised to
	 * {@code 0.0} exactly as upstream's {@code PJ_LP lp = {0.0, 0.0}} does.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double V = Double.NaN, c, p, phi = 0.0;
		int i;

		p = 0.5 * xyy;
		for (i = NITER; i > 0 ; --i) {
			c = Math.cos(0.5 * phi);
			phi -= V = (phi - Math.tan(phi / 2) - p) / (1. - 0.5 / (c * c));
			if (Math.abs(V) < EPS)
				break;
		}
		if (i == 0) {
			throw new ConvergenceFailureException(this,
					"inverse latitude iteration did not converge to " + EPS + " within " + NITER
							+ " iterations for northing " + xyy + " (last correction " + V + ")");
		}
		out.y = phi;
		out.x = 2. * xyx / (1. + Math.cos(phi));
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Nell-Hammer";
	}

}

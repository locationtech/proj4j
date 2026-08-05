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
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

public class FoucautSinusoidalProjection extends Projection {

	private static final long serialVersionUID = 7961458105562012064L;

	private double n, n1;

	private final static int MAX_ITER = 10;
	private final static double LOOP_TOL = 1e-7;

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double t;

		t = Math.cos(lpphi);
		out.x = lplam * t / (n + n1 * t);
		out.y = n * lpphi + n1 * Math.sin(lpphi);
		return out;
	}

	/**
	 * Inverse projection.
	 * <p>
	 * <b>Fail-closed.</b> PROJ's {@code fouc_s.cpp} clamps to {@code ±M_HALFPI} — the pole —
	 * when the Newton iteration exhausts {@code MAX_ITER}. Proj4J throws instead.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double V = Double.NaN;
		int i;

		if (n != 0) {
			out.y = xyy;
			for (i = MAX_ITER; i > 0; --i) {
				out.y -= V = (n * out.y + n1 * Math.sin(out.y) - xyy ) /
					(n + n1 * Math.cos(out.y));
				if (Math.abs(V) < LOOP_TOL)
					break;
			}
			if (i == 0) {
				throw new ConvergenceFailureException(this,
						"inverse latitude iteration did not converge to " + LOOP_TOL + " within "
								+ MAX_ITER + " iterations for northing " + xyy
								+ " (last correction " + V + ")");
			}
		} else
			out.y = ProjectionMath.asin(xyy);
		V = Math.cos(out.y);
		out.x = xyx * (n + n1 * V) / V;
		return out;
	}

	/**
	 * {@code +n}: the weight given to the sinusoidal component, in {@code [0, 1]}.
	 * <p>
	 * <b>This setter is why the bridge may claim to honour {@code "n"}.</b> {@code fouc_s.cpp:61}
	 * reads {@code pj_param(..., "dn").f} and {@code fouc_s} is registered in {@code Registry}, so
	 * before this existed there was no way for the parser to deliver the value and
	 * {@code +proj=fouc_s +n=0.5} would silently have used the default of 0. Nothing was actually
	 * wrong, only because {@code builtins.gie}'s single {@code fouc_s} block is a bare
	 * {@code +proj=fouc_s +a=6400000} — that is a property of the corpus, not of the code, and it is
	 * exactly the shape of latent defect that "a plausible wrong answer" names.
	 * <p>
	 * {@code Proj4Parser} dispatches {@code +n} to {@code urmfps}, {@code urm5} and {@code gn_sinu}
	 * on the concrete class; a branch for this class has to be added there for the key to arrive.
	 *
	 * @param n the weight, {@code 0 <= n <= 1}
	 */
	public void setN(double n) {
		this.n = n;
	}

	public double getN() {
		return n;
	}

	/**
	 * {@code PJ_PROJECTION(fouc_s)}.
	 * <p>
	 * {@code n1} is derived from {@code n} and nothing derived is read back, so the second call
	 * {@code Proj4Parser} makes is a no-op.
	 */
	public void initialize() {
		super.initialize();
		if (n < 0. || n > 1.)
			throw new ProjectionException("Invalid value for +n: it should be in [0, 1], but is " + n);
		n1 = 1. - n;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Foucaut Sinusoidal";
	}

}

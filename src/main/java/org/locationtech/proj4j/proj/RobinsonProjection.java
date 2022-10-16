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
import org.locationtech.proj4j.util.ProjectionMath;

public class RobinsonProjection extends PseudoCylindricalProjection {

	private final static double X[][] = {
			{1.0f, 2.2199e-17f, -7.15515e-05f, 3.1103e-06f},
			{0.9986f, -0.000482243f, -2.4897e-05f, -1.3309e-06f},
			{0.9954f, -0.00083103f, -4.48605e-05f, -9.86701e-07f},
			{0.99f, -0.00135364f, -5.9661e-05f, 3.6777e-06f},
			{0.9822f, -0.00167442f, -4.49547e-06f, -5.72411e-06f},
			{0.973f, -0.00214868f, -9.03571e-05f, 1.8736e-08f},
			{0.96f, -0.00305085f, -9.00761e-05f, 1.64917e-06f},
			{0.9427f, -0.00382792f, -6.53386e-05f, -2.6154e-06f},
			{0.9216f, -0.00467746f, -0.00010457f, 4.81243e-06f},
			{0.8962f, -0.00536223f, -3.23831e-05f, -5.43432e-06f},
			{0.8679f, -0.00609363f, -0.000113898f, 3.32484e-06f},
			{0.835f, -0.00698325f, -6.40253e-05f, 9.34959e-07f},
			{0.7986f, -0.00755338f, -5.00009e-05f, 9.35324e-07f},
			{0.7597f, -0.00798324f, -3.5971e-05f, -2.27626e-06f},
			{0.7186f, -0.00851367f, -7.01149e-05f, -8.6303e-06f},
			{0.6732f, -0.00986209f, -0.000199569f, 1.91974e-05f},
			{0.6213f, -0.010418f, 8.83923e-05f, 6.24051e-06f},
			{0.5722f, -0.00906601f, 0.000182f, 6.24051e-06f},
			{0.5322f, -0.00677797f, 0.000275608f, 6.24051e-06f}
	};

	private final static double Y[][] = {
			{-5.20417e-18f, 0.0124f, 1.21431e-18f, -8.45284e-11f},
			{0.062f, 0.0124f, -1.26793e-09f, 4.22642e-10f},
			{0.124f, 0.0124f, 5.07171e-09f, -1.60604e-09f},
			{0.186f, 0.0123999f, -1.90189e-08f, 6.00152e-09f},
			{0.248f, 0.0124002f, 7.10039e-08f, -2.24e-08f},
			{0.31f, 0.0123992f, -2.64997e-07f, 8.35986e-08f},
			{0.372f, 0.0124029f, 9.88983e-07f, -3.11994e-07f},
			{0.434f, 0.0123893f, -3.69093e-06f, -4.35621e-07f},
			{0.4958f, 0.0123198f, -1.02252e-05f, -3.45523e-07f},
			{0.5571f, 0.0121916f, -1.54081e-05f, -5.82288e-07f},
			{0.6176f, 0.0119938f, -2.41424e-05f, -5.25327e-07f},
			{0.6769f, 0.011713f, -3.20223e-05f, -5.16405e-07f},
			{0.7346f, 0.0113541f, -3.97684e-05f, -6.09052e-07f},
			{0.7903f, 0.0109107f, -4.89042e-05f, -1.04739e-06f},
			{0.8435f, 0.0103431f, -6.4615e-05f, -1.40374e-09f},
			{0.8936f, 0.00969686f, -6.4636e-05f, -8.547e-06f},
			{0.9394f, 0.00840947f, -0.000192841f, -4.2106e-06f},
			{0.9761f, 0.00616527f, -0.000256f, -4.2106e-06f},
			{1.0f, 0.00328947f, -0.000319159f, -4.2106e-06f}
	};

	private final int NODES = 18;
	private final static double FXC = 0.8487;
	private final static double FYC = 1.3523;
	private final static double C1 = 11.45915590261646417544;
	private final static double RC1 = 0.08726646259971647884;
	private final static double ONEEPS = 1.000001;
	private final static double EPS = 1e-10;
	private final static int MAX_ITER = 100;
	
	public RobinsonProjection() {
	}

	private double V(double[] C, double z) {
		return C[0] + z * (C[1] + z * (C[2] + z * C[3]));
	}

	private double DV(double[] C, double z) {
		return C[1] + 2 * z * C[2] + z * z * 3. * C[3];
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		double phi = Math.abs(lpphi);
		int i = (int)Math.floor(phi * C1);
		if (i >= NODES)
			i = NODES;
		phi = Math.toDegrees(phi - RC1 * i);
		xy.x = V(X[i], phi) * FXC * lplam;
		xy.y = V(Y[i], phi) * FYC;
		if (lpphi < 0.0)
			xy.y = -xy.y;
		return xy;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		int i;
		double t, t1;

		lp.x = x / FXC;
		lp.y = Math.abs(y / FYC);
		if (lp.y >= 1.0) {
			if (lp.y > ONEEPS) {
				lp.x = Double.NaN;
				lp.y = Double.NaN;
				return lp;
			} else {
				lp.y = y < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
				lp.x /= X[NODES][0];
			}
		} else {
			i = (int) Math.floor(lp.y * NODES);
			if( i < 0 || i >= NODES ) {
				lp.x = Double.NaN;
				lp.y = Double.NaN;
				return lp;
			}
			for (;;) {
				if (Y[i][0] > lp.y) --i;
				else if (Y[i+1][0] <= lp.y) ++i;
				else break;
			}
			double[] T = Y[i];
			t = 5. * (lp.y - Y[i][0])/(Y[i+1][0] - Y[i][0]);
			int iters;
			for (iters = MAX_ITER; iters > 0; --iters) { // Newton-Raphson
				t1 = (V(T, t) - lp.y) / DV(T, t);
				t -= t1;
				if (Math.abs(t1) < EPS)
					break;
			}
			lp.y = Math.toRadians(5 * i + t);
			if (y < 0.)
				lp.y = -lp.y;
			lp.x /= V(X[i], t);
			if( Math.abs(lp.x) > ProjectionMath.PI ) {
				lp.x = Double.NaN;
				lp.y = Double.NaN;
			}
		}
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Robinson";
	}

}

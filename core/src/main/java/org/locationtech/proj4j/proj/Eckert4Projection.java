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

public class Eckert4Projection extends Projection {

	private final static double C_x = .42223820031577120149;
	private final static double C_y = 1.32650042817700232218;
	private final static double RC_y = .75386330736002178205;
	private final static double C_p = 3.57079632679489661922;
	private final static double RC_p = .28004957675577868795;

	// s - sin(s), accurate also for small |s|, where the subtraction would lose most of the digits
	private static double sMinusSinS(double s) {
		if (Math.abs(s) < 0.25) {
			double s2 = s * s;
			return s * s2 * (1. / 6 - s2 * (1. / 120 - s2 * (1. / 5040 - s2 * (1. / 362880
				- s2 * (1. / 39916800 - s2 / 6227020800.)))));
		}
		return s - Math.sin(s);
	}

	// t - sin(2t) / 2 + 2 * (1 - cos(t)), with theta = pi / 2 - t, accurate for small t
	private static double polarD(double t) {
		double h = Math.sin(0.5 * t);
		return 0.5 * sMinusSinS(2. * t) + 4. * h * h;
	}
	private final static double EPS = 1e-7;
	private final int NITER = 6;

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double p, V, s, c;
		int i;

		if (Math.abs(lpphi) > Math.PI / 2 - 0.1) {
			// Close to the poles the Newton iteration below converges only linearly. With
			// theta = pi/2 - t and chi the colatitude, solve
			// t - sin(2t)/2 + 2 * (1 - cos(t)) = 2 * C_p * sin(chi / 2)^2
			double h = Math.sin(0.5 * (Math.PI / 2 - Math.abs(lpphi)));
			double w = 2. * C_p * h * h;
			double t = Math.sqrt(w); // >= the root, as the left side is >= t^2
			for (i = 0; i < 30; ++i) {
				double st = Math.sin(t);
				double d = 2. * st * (1. + st);
				if (d == 0.)
					break;
				double dt = (polarD(t) - w) / d;
				t -= dt;
				if (Math.abs(dt) <= 1e-12 * t)
					break;
			}
			// cos(theta) = sin(t), sin(theta) = cos(t)
			out.x = C_x * lplam * (1. + Math.sin(t));
			out.y = Math.copySign(C_y * Math.cos(t), lpphi);
			return out;
		}
		p = C_p * Math.sin(lpphi);
		V = lpphi * lpphi;
		lpphi *= 0.895168 + V * ( 0.0218849 + V * 0.00826809 );
		for (i = NITER; i > 0; --i) {
			c = Math.cos(lpphi);
			s = Math.sin(lpphi);
			lpphi -= V = (lpphi + s * (c + 2.) - p) /
				(1. + c * (c + 2.) - s * s);
			if (Math.abs(V) < EPS)
				break;
		}
		if (i == 0) {
			out.x = C_x * lplam;
			out.y = lpphi < 0. ? -C_y : C_y;
		} else {
			out.x = C_x * lplam * (1. + Math.cos(lpphi));
			out.y = C_y * Math.sin(lpphi);
		}
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double c;

		if (Math.abs(xyy) > 0.5 * C_y && Math.abs(xyy) <= C_y) {
			// Close to the poles: theta = pi/2 - t, and the colatitude chi from
			// 2 * C_p * sin(chi / 2)^2 = t - sin(2t)/2 + 2 * (1 - cos(t)), instead of asin()
			// of a value close to 1
			double t = 2. * Math.asin(Math.sqrt(0.5 * (C_y - Math.abs(xyy)) * RC_y));
			out.x = xyx / (C_x * (1. + Math.sin(t)));
			out.y = Math.copySign(Math.PI / 2 - 2. * Math.asin(Math.sqrt(0.5 * polarD(t) * RC_p)), xyy);
			return out;
		}
		out.y = ProjectionMath.asin(xyy / C_y);
		out.x = xyx / (C_x * (1. + (c = Math.cos(out.y))));
		out.y = ProjectionMath.asin((out.y + Math.sin(out.y) * (c + 2.)) / C_p);
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

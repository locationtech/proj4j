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

public class PutninsP2Projection extends Projection {

	private final static double C_x = 1.89490;
	private final static double C_y = 1.71848;
	private final static double C_p = 0.6141848493043784;
	private final static double EPS = 1e-10;
	private final static int NITER = 10;
	private final static double PI_DIV_3 = 1.0471975511965977;

	// Close to the poles, where theta tends to pi/3 and the Newton iteration converges only
	// linearly: with theta = pi/3 - t and F(theta) = theta + sin(theta) * (cos(theta) - 1),
	// so that C_p = F(pi/3), F(pi/3) - F(pi/3 - t) = t + cos(2pi/3 - t) * sin(t)
	// - 2 * cos(pi/3 - t/2) * sin(t/2)
	private static double polarD(double t) {
		return t + Math.cos(2. * PI_DIV_3 - t) * Math.sin(t)
			- 2. * Math.cos(PI_DIV_3 - 0.5 * t) * Math.sin(0.5 * t);
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double p, c, s, V, phi;
		int i;

		if (Math.abs(lpphi) > Math.PI / 2 - 0.1) {
			// solve F(pi/3) - F(pi/3 - t) = C_p * (1 - sin|phi|) = 2 * C_p * sin(chi / 2)^2
			double h = Math.sin(0.5 * (Math.PI / 2 - Math.abs(lpphi)));
			double w = 2. * C_p * h * h;
			double t = Math.sqrt(w / (0.25 * Math.sqrt(3.))); // D(t) ~ sqrt(3)/4 * t^2
			for (i = 0; i < 30; ++i) {
				// derivative of D: cos(theta) * (2 * cos(theta) - 1),
				// with 2 * cos(theta) - 1 = 4 * sin(pi/3 - t/2) * sin(t/2)
				double d = Math.cos(PI_DIV_3 - t) * 4. * Math.sin(PI_DIV_3 - 0.5 * t) * Math.sin(0.5 * t);
				if (d == 0.)
					break;
				double dt = (polarD(t) - w) / d;
				t -= dt;
				if (t < 0.)
					t = 0.;
				// D(t) carries a rounding error of about eps * t: also stop on an absolute step
				if (Math.abs(dt) <= 1e-12 * t || Math.abs(dt) < 1e-15)
					break;
			}
			// cos(theta) - 1/2 = 2 * sin(pi/3 - t/2) * sin(t/2)
			out.x = C_x * lplam * 2. * Math.sin(PI_DIV_3 - 0.5 * t) * Math.sin(0.5 * t);
			out.y = Math.copySign(C_y * Math.sin(PI_DIV_3 - t), lpphi);
			return out;
		}
		p = C_p * Math.sin(lpphi);
		s = lpphi * lpphi;
		phi = lpphi * (0.615709 + s * ( 0.00909953 + s * 0.0046292 ));
		for (i = NITER; i > 0; --i) {
			c = Math.cos(phi);
			s = Math.sin(phi);
			phi -= V = (phi + s * (c - 1.) - p) /
				(1. + c * (c - 1.) - s * s);
			if (Math.abs(V) < EPS)
				break;
		}
		if (i == 0)
			phi = phi < 0 ? - PI_DIV_3 : PI_DIV_3;
		out.x = C_x * lplam * (Math.cos(phi) - 0.5);
		out.y = C_y * Math.sin(phi);
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double c;

		out.y = ProjectionMath.asin(xyy / C_y);
		if (Math.abs(out.y) > PI_DIV_3 - 0.5) {
			// Close to the poles: theta = +/-(pi/3 - t), and the colatitude chi from
			// 2 * C_p * sin(chi / 2)^2 = F(pi/3) - F(pi/3 - t), instead of asin() near 1
			double t = Math.max(0., PI_DIV_3 - Math.abs(out.y));
			double den = C_x * 2. * Math.sin(PI_DIV_3 - 0.5 * t) * Math.sin(0.5 * t);
			out.x = den == 0. ? 0. : xyx / den;
			out.y = Math.copySign(Math.PI / 2 - 2. * Math.asin(Math.sqrt(0.5 * polarD(t) / C_p)), xyy);
			return out;
		}
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

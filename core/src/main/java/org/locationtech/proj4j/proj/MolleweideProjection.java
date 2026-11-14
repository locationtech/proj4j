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

import java.util.Objects;

import org.locationtech.proj4j.ProjCoordinate;

public class MolleweideProjection extends PseudoCylindricalProjection {

	public static final int MOLLEWEIDE = 0;
	public static final int WAGNER4 = 1;
	public static final int WAGNER5 = 2;

	// 30 iterations, as in PROJ (with 10 the iteration gave up and snapped to the pole)
	private static final int MAX_ITER = 30;
	private static final double TOLERANCE = 1e-7;

	private int type = MOLLEWEIDE;
	private double cx, cy, cp;
	// p = pi/2: the poles get the special handling below
	private boolean mollweide = false;

	// s - sin(s), accurate also for small |s|, where the subtraction would lose most of the digits
	private static double sMinusSinS(double s) {
		if (Math.abs(s) < 0.25) {
			double s2 = s * s;
			return s * s2 * (1. / 6 - s2 * (1. / 120 - s2 * (1. / 5040 - s2 * (1. / 362880
				- s2 * (1. / 39916800 - s2 / 6227020800.)))));
		}
		return s - Math.sin(s);
	}

	// Mollweide close to the poles. With theta = (pi - s) / 2 and chi the colatitude,
	// 2 * theta + sin(2 * theta) = pi * sin(phi) becomes s - sin(s) = 2 * pi * sin(chi / 2)^2,
	// which can be solved accurately where the Newton iteration on theta converges only
	// linearly. Returns s.
	private static double mollweidePolarS(double chi) {
		double sh = Math.sin(0.5 * chi);
		double w = 2. * Math.PI * sh * sh;
		double s = Math.cbrt(6. * w); // <= the root, as s - sin(s) <= s^3 / 6
		for (int i = 0; i < 20; i++) {
			double sh2 = Math.sin(0.5 * s);
			double d = 2. * sh2 * sh2; // 1 - cos(s)
			if (d == 0.)
				break;
			double ds = (sMinusSinS(s) - w) / d;
			s -= ds;
			if (Math.abs(ds) <= 1e-12 * s)
				break;
		}
		return s;
	}

	public MolleweideProjection() {
		this(Math.PI/2);
	}

	public MolleweideProjection(int type) {
		this.type = type;
		switch (type) {
		case MOLLEWEIDE:
			init(Math.PI/2);
			break;
		case WAGNER4:
			init(Math.PI/3);
			break;
		case WAGNER5:
			init(Math.PI/2);
			cx = 0.90977;
			cy = 1.65014;
			cp = 3.00896;
			mollweide = false;
			break;
		}
	}

	public MolleweideProjection(double p) {
		init(p);
	}

	public void init(double p) {
		double r, sp, p2 = p + p;

		sp = Math.sin(p);
		r = Math.sqrt(Math.PI*2.0 * sp / (p2 + Math.sin(p2)));
		cx = 2. * r / Math.PI;
		cy = r / sp;
		cp = p2 + Math.sin(p2);
		mollweide = p == Math.PI / 2;
	}

	public MolleweideProjection(double cx, double cy, double cp) {
		this.cx = cx;
		this.cy = cy;
		this.cp = cp;
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate xy) {
		double k, v;
		int i;

		if (mollweide && Math.abs(lpphi) > Math.PI / 2 - 0.5) {
			double s = mollweidePolarS(Math.PI / 2 - Math.abs(lpphi));
			// cos(theta) = sin(s / 2), sin(theta) = cos(s / 2)
			xy.x = cx * lplam * Math.sin(0.5 * s);
			xy.y = Math.copySign(cy * Math.cos(0.5 * s), lpphi);
			return xy;
		}
		k = cp * Math.sin(lpphi);
		for (i = MAX_ITER; i != 0; i--) {
			lpphi -= v = (lpphi + Math.sin(lpphi) - k) / (1. + Math.cos(lpphi));
			if (Math.abs(v) < TOLERANCE)
				break;
		}
		if (i == 0)
			lpphi = (lpphi < 0.) ? -Math.PI/2 : Math.PI/2;
		else
			lpphi *= 0.5;
		xy.x = cx * lplam * Math.cos(lpphi);
		xy.y = cy * Math.sin(lpphi);
		return xy;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		double lat, lon;

		if (mollweide && Math.abs(y) > 0.5 * cy && Math.abs(y) <= cy) {
			// Close to the poles: cos(s / 2) = |y| / cy, with s as in mollweidePolarS(), and
			// the colatitude chi from 2 * sin(chi / 2)^2 = (s - sin(s)) / pi, instead of
			// asin() of a value close to 1
			double halfS = 2. * Math.asin(Math.sqrt(0.5 * (cy - Math.abs(y)) / cy));
			double den = cx * Math.sin(halfS);
			lp.x = den == 0. ? 0. : x / den; // at the pole x is 0
			lp.y = Math.copySign(Math.PI / 2 - 2. * Math.asin(Math.sqrt(sMinusSinS(2. * halfS) / (2. * Math.PI))), y);
			return lp;
		}
		lat = Math.asin(y / cy);
		lon = x / (cx * Math.cos(lat));
		lat += lat;
		lat = Math.asin((lat + Math.sin(lat)) / cp);
		lp.x = lon;
		lp.y = lat;
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isEqualArea() {
	    return true;
	}

	public String toString() {
		switch (type) {
		case WAGNER4:
			return "Wagner IV";
		case WAGNER5:
			return "Wagner V";
		}
		return "Molleweide";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof MolleweideProjection) {
					MolleweideProjection p = (MolleweideProjection) that;
					return (
						type == p.type &&
						cx == p.cx &&
						cy == p.cy &&
						cp == p.cp &&
						super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(type, cx, cy, cp, super.hashCode());
	}
}

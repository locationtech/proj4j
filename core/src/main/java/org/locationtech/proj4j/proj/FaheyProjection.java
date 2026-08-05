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

public class FaheyProjection extends Projection {

	private static final long serialVersionUID = -4707229334836534388L;

	private final static double TOL = 1e-6;
	
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		out.y = 1.819152 * ( out.x = Math.tan(0.5 * lpphi) );
		out.x = 0.819152 * lplam * asqrt(1 - out.x * out.x);
		return out;
	}

	/**
	 * Port of {@code fahey_s_inverse} ({@code 9.8.1:src/projections/fahey.cpp:24-33}).
	 * <p>
	 * <b>Three separate transcription faults in four lines, all silent.</b> Upstream divides
	 * {@code xy.y} by 1.819152 <em>first</em> and every later use reads the divided value; the old
	 * code divided {@code out.y} — the caller's output slot, i.e. uninitialised as far as this
	 * method is concerned — instead of {@code xyy}, then formed {@code 1 - xyy*xyy} from the
	 * <em>undivided</em> northing, and finally took {@code sqrt(xyy)} rather than
	 * {@code sqrt(1 - y*y)}. Upstream's guard is also on {@code 1 - y*y}, not on
	 * {@code |1 - y*y|}, and returns longitude 0 there; that asymmetry is reproduced.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double y = xyy / 1.819152;
		out.y = 2. * Math.atan(y);
		double c = 1. - y * y;
		out.x = Math.abs(c) < TOL ? 0. : xyx / (0.819152 * Math.sqrt(c));
		return out;
	}

	private double asqrt(double v) {
		return (v <= 0) ? 0. : Math.sqrt(v);
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Fahey";
	}

}

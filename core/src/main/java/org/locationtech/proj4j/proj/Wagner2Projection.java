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
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

public class Wagner2Projection extends Projection {

	private static final long serialVersionUID = 5110056924073256024L;

	private final static double C_x = 0.92483;
	private final static double C_y = 1.38725;
	private final static double C_p1 = 0.88022;
	private final static double C_p2 = 0.88550;

	/**
	 * Port of {@code wag2_s_forward} ({@code 9.8.1:src/projections/wag2.cpp:14-20}).
	 * <p>
	 * <b>Upstream reassigns {@code lp.phi} in place</b> — {@code lp.phi = aasin(C_p1 * sin(C_p2 *
	 * lp.phi))} — and both following lines read the reassigned value. The previous transcription
	 * dropped that result into {@code out.y}, overwrote it on the next line but one, and used the
	 * <em>original</em> latitude in both, so the northing was {@code C_y * phi} instead of
	 * {@code C_y * aasin(...)}: 34.2 km at {@code +proj=wag2 +a=6400000} and {@code (2, 1)}. Same
	 * defect, same shape, as {@link UrmaevFlatPolarSinusoidalProjection}.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double phi = ProjectionMath.asinChecked(C_p1 * FastStrictTrig.sin(C_p2 * lpphi));
		out.x = C_x * lplam * FastStrictTrig.cos(phi);
		out.y = C_y * phi;
		return out;
	}

	/** Port of {@code wag2_s_inverse} ({@code 9.8.1:src/projections/wag2.cpp:22-28}). */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double phi = xyy / C_y;
		out.x = xyx / (C_x * FastStrictTrig.cos(phi));
		out.y = ProjectionMath.asinChecked(FastStrictTrig.sin(phi) / C_p1) / C_p2;
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Wagner II";
	}
}

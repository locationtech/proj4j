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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

public class McBrydeThomasFlatPolarParabolicProjection extends Projection {

	private static final long serialVersionUID = -1265108225027350150L;

	private final static double CS = .95257934441568037152;
	private final static double FXC = .92582009977255146156;
	private final static double FYC = 3.40168025708304504493;
	private final static double C23 = .66666666666666666666;
	private final static double C13 = .33333333333333333333;
	private final static double ONEEPS = 1.0000001;

	/**
	 * Port of {@code mbtfpp_s_forward} ({@code 9.8.1:src/projections/mbtfpp.cpp:18-25}).
	 * <p>
	 * <b>Upstream reassigns {@code lp.phi = asin(CSy * sin(lp.phi))} and both following lines read
	 * the reassigned value.</b> The previous transcription computed it into {@code out.y}, then
	 * overwrote {@code out.y} and used the <em>original</em> latitude in both formulas — 6.0 km at
	 * {@code +proj=mbtfpp +a=6400000} and {@code (2, 1)}. The inverse never had the defect, so the
	 * two directions were not mutual inverses. Note the constant is upstream's {@code CSy}; the
	 * field is named {@code CS} here for source compatibility.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double phi = ProjectionMath.asinChecked(CS * FastStrictTrig.sin(lpphi));
		out.x = FXC * lplam * (2. * FastStrictTrig.cos(C23 * phi) - 1.);
		out.y = FYC * FastStrictTrig.sin(C13 * phi);
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		out.y = xyy / FYC;
		if (Math.abs(out.y) >= 1.) {
			if (Math.abs(out.y) > ONEEPS)	throw new ProjectionException("I");
			else	out.y = (out.y < 0.) ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		} else
			out.y = Math.asin(out.y);
		out.x = xyx / ( FXC * (2. * Math.cos(C23 * (out.y *= 3.)) - 1.) );
		if (Math.abs(out.y = Math.sin(out.y) / CS) >= 1.) {
			if (Math.abs(out.y) > ONEEPS)	throw new ProjectionException("I");
			else	out.y = (out.y < 0.) ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		} else
			out.y = Math.asin(out.y);
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "McBride-Thomas Flat-Polar Parabolic";
	}

}

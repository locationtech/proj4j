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

import org.locationtech.proj4j.ErrorCause;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

public class CollignonProjection extends Projection {

	private static final long serialVersionUID = 7340703384480636661L;

	private final static double FXC = 1.12837916709551257390;
	private final static double FYC = 1.77245385090551602729;
	private final static double ONEEPS = 1.0000001;

	/** Port of {@code collg_s_forward} ({@code 9.8.1:src/projections/collg.cpp:14-25}). */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		if ((out.y = 1. - FastStrictTrig.sin(lpphi)) <= 0.)
			out.y = 0.;
		else
			out.y = Math.sqrt(out.y);
		out.x = FXC * lplam * out.y;
		out.y = FYC * (1. - out.y);
		return out;
	}

	/**
	 * Port of {@code collg_s_inverse} ({@code 9.8.1:src/projections/collg.cpp:27-45}).
	 * <p>
	 * <b>The previous transcription returned {@code xy.y / FYC - 1} as the latitude, in radians,
	 * unconditionally.</b> Upstream reassigns {@code lp.phi} twice — first to
	 * {@code xy.y / FYC - 1}, then to {@code 1 - lp.phi * lp.phi}, and it is the <em>second</em>
	 * value that {@code asin} is applied to. The old code applied {@code asin} to the first,
	 * tested the second, and then overwrote the result with the first again on its last line, so
	 * every inverse latitude was the raw scaled northing: at {@code +proj=collg +a=6400000} and
	 * {@code (200, 100)} it answered &minus;57.2953&deg; against PROJ's 0.001010&deg; — 6,375 km,
	 * and 1.0 radian expressed as degrees, which is the signature of exactly this substitution.
	 * The longitude was wrong for the same reason, taking {@code 1 - sin(phi)} of the wrong angle.
	 * <p>
	 * The three-way guard is upstream's, including the asymmetry that {@code |lp.phi| < 1} takes
	 * {@code asin} while {@code |lp.phi|} between 1 and {@code ONEEPS} clamps to a pole and
	 * anything larger raises.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double phi = xyy / FYC - 1.;
		phi = 1. - phi * phi;
		if (Math.abs(phi) < 1.) {
			phi = Math.asin(phi);
		} else if (Math.abs(phi) > ONEEPS) {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"Collignon inverse: 1 - (y/FYC - 1)^2 = " + phi + " is past ONEEPS = " + ONEEPS
							+ ", so the northing is outside the map");
		} else {
			phi = phi < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		}
		double c = 1. - FastStrictTrig.sin(phi);
		out.x = c <= 0. ? 0. : xyx / (FXC * Math.sqrt(c));
		out.y = phi;
		return out;
	}

	/**
	 * Returns true if this projection is equal area
	 */
	public boolean isEqualArea() {
		return true;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Collignon";
	}

}

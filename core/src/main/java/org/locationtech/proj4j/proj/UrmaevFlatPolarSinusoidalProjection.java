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

import java.util.Objects;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

public class UrmaevFlatPolarSinusoidalProjection extends Projection {

	private static final long serialVersionUID = -2557543472419612430L;

	private final static double C_x = 0.8773826753;
	private final static double Cy = 1.139753528477;

	private double n = 0.8660254037844386467637231707;// wag1
	private double C_y;

	public UrmaevFlatPolarSinusoidalProjection() {
	}

	/**
	 * Port of {@code urmfps_s_forward} ({@code 9.8.1:src/projections/urmfps.cpp:19-27}).
	 * <p>
	 * <b>Upstream reassigns {@code lp.phi} in place and then uses it twice</b>; the previous
	 * transcription computed the reassignment into {@code out.y}, immediately overwrote it, and fed
	 * the <em>original</em> latitude to both {@code cos} and the {@code C_y} product. Since
	 * {@code C_y = Cy / n}, that made the northing a factor of {@code 1/n} too large: at
	 * {@code +proj=wag1 +a=6400000} and {@code (2, 1)} it returned {@code y = 147,006.878} against
	 * PROJ's {@code 127,310.075} — 19.7 km — and the easting 7.46 m off through the {@code cos}.
	 * The inverse never had the defect, so forward and inverse were not mutual inverses either.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double phi = ProjectionMath.asinChecked(n * FastStrictTrig.sin(lpphi));
		out.x = C_x * lplam * FastStrictTrig.cos(phi);
		out.y = C_y * phi;
		return out;
	}

	/** Port of {@code urmfps_s_inverse} ({@code 9.8.1:src/projections/urmfps.cpp:29-36}). */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		xyy /= C_y;
		out.y = ProjectionMath.asinChecked(FastStrictTrig.sin(xyy) / n);
		out.x = xyx / (C_x * FastStrictTrig.cos(xyy));
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public void initialize() { // urmfps
		super.initialize();
		if (n <= 0. || n > 1.)
			throw new ProjectionException("-40");
		C_y = Cy / n;
	}

	// Properties
	public void setN( double n ) {
		this.n = n;
	}

	public double getN() {
		return n;
	}

	public String toString() {
		return "Urmaev Flat-Polar Sinusoidal";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof UrmaevFlatPolarSinusoidalProjection) {
					UrmaevFlatPolarSinusoidalProjection p = (UrmaevFlatPolarSinusoidalProjection) that;
					return (n == p.n) && super.equals(that);
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(n, super.hashCode());
	}
}

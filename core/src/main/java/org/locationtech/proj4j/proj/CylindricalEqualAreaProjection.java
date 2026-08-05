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
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.AuthalicLat;
import org.locationtech.proj4j.util.ProjectionMath;

public class CylindricalEqualAreaProjection extends Projection {

	private static final long serialVersionUID = -4997310699865808074L;

	private double qp;

	/**
	 * The order-6 authalic-latitude machinery, {@code 9.8.1:src/latitudes.cpp}. Replaces
	 * {@code ProjectionMath.authset}/{@code authlat}/{@code qsfn}.
	 * <p>
	 * Note that {@code cea}'s <em>forward</em> genuinely wants the raw {@code q}
	 * ({@code 9.8.1:cea.cpp:21}: {@code xy.y = 0.5 * pj_authalic_lat_q(sin(phi)) / k0}), so
	 * unlike {@code laea} it does not move to the direct {@code phi -> xi} series; only the
	 * inverse series changes, from third to sixth order.
	 */
	private AuthalicLat authalic;

	public CylindricalEqualAreaProjection() {
		this(0.0, 0.0, 0.0);
	}

	public CylindricalEqualAreaProjection(double projectionLatitude, double projectionLongitude, double trueScaleLatitude) {
		this.projectionLatitude = projectionLatitude;
		this.projectionLongitude = projectionLongitude;
		this.trueScaleLatitude = trueScaleLatitude;
		initialize();
	}

	public void initialize() {
		super.initialize();
		double t = trueScaleLatitude;

		scaleFactor = Math.cos(t);
		if (es != 0) {
			t = Math.sin(t);
			scaleFactor /= Math.sqrt(1. - es * t * t);
			authalic = new AuthalicLat(es);
			qp = authalic.qp();
		}
	}

	public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
		if (spherical) {
			xy.x = scaleFactor * lam;
			xy.y = Math.sin(phi) / scaleFactor;
		} else {
			xy.x = scaleFactor * lam;
			xy.y = .5 * authalic.q(Math.sin(phi)) / scaleFactor;
		}
		return xy;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		if (spherical) {
			double t;

			if ((t = Math.abs(y *= scaleFactor)) - EPS10 <= 1.) {
				if (t >= 1.)
					lp.y = y < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
				else
					lp.y = Math.asin(y);
				lp.x = x / scaleFactor;
			} else throw new ProjectionException();
		} else {
			lp.y = authalic.inverse(Math.asin( 2. * y * scaleFactor / qp));
			lp.x = x / scaleFactor;
		}
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isRectilinear() {
		return true;
	}

}

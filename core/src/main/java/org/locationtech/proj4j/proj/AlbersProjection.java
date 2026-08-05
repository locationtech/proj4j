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

public class AlbersProjection extends Projection {

	private static final long serialVersionUID = -8897646364090205147L;

	private final static double EPS10 = 1.e-10;
	private final static double TOL7 = 1.e-7;
	private double ec;
	private double n;
	private double c;
	private double dd;
	private double n2;
	private double rho0;
	private double phi1;
	private double phi2;
	private double qp;

	/**
	 * The order-6 authalic-latitude machinery, {@code 9.8.1:src/latitudes.cpp}. Replaces
	 * {@code ProjectionMath.qsfn} in the forward direction and the 15-step {@code phi1_}
	 * Newton loop in the inverse.
	 */
	private AuthalicLat authalic;

	//protected double projectionLatitude1 = MapMath.degToRad(45.5);
	//protected double projectionLatitude2 = MapMath.degToRad(29.5);

	public AlbersProjection() {
		minLatitude = ProjectionMath.toRad(0);
		maxLatitude = ProjectionMath.toRad(80);
		projectionLatitude1 = ProjectionMath.degToRad(45.5);
		projectionLatitude2 = ProjectionMath.degToRad(29.5);
		initialize();
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double rho;
		if ((rho = c - (!spherical ? n * authalic.q(Math.sin(lpphi)) : n2 * Math.sin(lpphi))) < 0.)
			throw new ProjectionException("F");
		rho = dd * Math.sqrt(rho);
		out.x = rho * Math.sin( lplam *= n );
		out.y = rho0 - rho * Math.cos(lplam);
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double rho;
		if ((rho = ProjectionMath.distance(xyx, xyy = rho0 - xyy)) != 0) {
			double lpphi, lplam;
			if (n < 0.) {
				rho = -rho;
				xyx = -xyx;
				xyy = -xyy;
			}
			lpphi =  rho / dd;
			if (!spherical) {
				final double qs = (c - lpphi * lpphi) / n;
				if (Math.abs(ec - Math.abs(qs)) > TOL7) {
					// 9.8.1:aea.cpp:100 -- outside the projection domain; asin(qs/qp)
					// would be NaN. proj4j had no such guard.
					if (Math.abs(qs) > 2.)
						throw new ProjectionException("aea inverse: |qs| > 2, outside the projection domain");
					lpphi = authalic.inverse(Math.asin(qs / qp));
				} else
					lpphi = qs < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
			} else {
				// 9.8.1:aea.cpp:113-118. The quantity PROJ names qs_div_2 is a *local*;
				// proj4j used out.y as a scratch temp and then took asin of lpphi, which
				// is still rho/dd -- the RADIUS. That is a 89.9568 degree error, and NaN
				// wherever rho/dd > 1. The sign test was on lpphi too, which is >= 0 by
				// construction, so the pole fallback could only ever return +90.
				final double qsDiv2 = (c - lpphi * lpphi) / n2;
				if (Math.abs(qsDiv2) <= 1.)
					lpphi = Math.asin(qsDiv2);
				else
					lpphi = qsDiv2 < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
			}
			lplam = Math.atan2(xyx, xyy) / n;
			out.x = lplam;
			out.y = lpphi;
		} else {
			out.x = 0.;
			out.y = n > 0. ? ProjectionMath.HALFPI : - ProjectionMath.HALFPI;
		}
		return out;
	}

	/**
	 * The cone's first standard parallel, radians. {@code aea} reads {@code +lat_1}
	 * ({@code 9.8.1:src/projections/aea.cpp:209}); {@code leac} does not, which is the only
	 * difference between the two operators upstream — hence the seam.
	 *
	 * @return {@link Projection#projectionLatitude1}
	 * @since 1.5.0
	 */
	protected double firstStandardParallel() {
		return projectionLatitude1;
	}

	/**
	 * The cone's second standard parallel, radians. {@code aea} reads {@code +lat_2}.
	 *
	 * @return {@link Projection#projectionLatitude2}
	 * @since 1.5.0
	 */
	protected double secondStandardParallel() {
		return projectionLatitude2;
	}

	public void initialize() {
		super.initialize();
		double cosphi, sinphi;
		boolean secant;

		phi1 = firstStandardParallel();
		phi2 = secondStandardParallel();

		if (Math.abs(phi1 + phi2) < EPS10)
			throw new ProjectionException("-21");
		n = sinphi = Math.sin(phi1);
		cosphi = Math.cos(phi1);
		secant = Math.abs(phi1 - phi2) >= EPS10;
		//spherical = es > 0.0;
		if (!spherical) {
			double ml1, m1;

			authalic = new AuthalicLat(es);
			qp = authalic.qp();
			m1 = ProjectionMath.msfn(sinphi, cosphi, es);
			ml1 = authalic.q(sinphi);
			if (secant) { /* secant cone */
				double ml2, m2;

				sinphi = Math.sin(phi2);
				cosphi = Math.cos(phi2);
				m2 = ProjectionMath.msfn(sinphi, cosphi, es);
				ml2 = authalic.q(sinphi);
				n = (m1 * m1 - m2 * m2) / (ml2 - ml1);
			}
			ec = 1. - .5 * one_es * Math.log((1. - e) /
				(1. + e)) / e;
			c = m1 * m1 + n * ml1;
			dd = 1. / n;
			rho0 = dd * Math.sqrt(c - n * authalic.q(Math.sin(projectionLatitude)));
		} else {
			if (secant) n = .5 * (n + Math.sin(phi2));
			n2 = n + n;
			c = cosphi * cosphi + n2 * sinphi;
			dd = 1. / n;
			rho0 = dd * Math.sqrt(c - n2 * Math.sin(projectionLatitude));
		}
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

	/**
	 * Returns the ESPG code for this projection, or 0 if unknown.
	 */
	public int getEPSGCode() {
		return 9822;
	}

	public String toString() {
		return "Albers Equal Area";
	}

}


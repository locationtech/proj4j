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
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * The Equidistant Conic projection, {@code +proj=eqdc}.
 * Requires {@code +lat_1} and {@code +lat_2}.
 */
public class EquidistantConicProjection extends ConicProjection {

	private final static double EPS10 = 1.e-10;

	private double phi1, phi2, n, rho0, c;
	private double[] en;
	private boolean ellips;

	public EquidistantConicProjection() {
		minLatitude = ProjectionMath.degToRad(10);
		maxLatitude = ProjectionMath.degToRad(70);
		minLongitude = ProjectionMath.degToRad(-90);
		maxLongitude = ProjectionMath.degToRad(90);
	}

	public void initialize() {
		super.initialize();
		double cosphi, sinphi;
		boolean secant;

		phi1 = projectionLatitude1;
		phi2 = projectionLatitude2;

		if (Math.abs(phi1) > ProjectionMath.HALFPI)
			throw new ProjectionException("Invalid value for lat_1: |lat_1| should be <= 90");
		if (Math.abs(phi2) > ProjectionMath.HALFPI)
			throw new ProjectionException("Invalid value for lat_2: |lat_2| should be <= 90");
		if (Math.abs(phi1 + phi2) < EPS10)
			throw new ProjectionException("Invalid value for lat_1 and lat_2: |lat_1 + lat_2| should be > 0");

		en = ProjectionMath.enfn(es);

		sinphi = Math.sin(phi1);
		n = sinphi;
		cosphi = Math.cos(phi1);
		secant = Math.abs(phi1 - phi2) >= EPS10;
		ellips = es > 0.;
		if (ellips) {
			double ml1, m1;

			m1 = ProjectionMath.msfn(sinphi, cosphi, es);
			ml1 = ProjectionMath.mlfn(phi1, sinphi, cosphi, en);
			if (secant) { /* secant cone */
				sinphi = Math.sin(phi2);
				cosphi = Math.cos(phi2);
				double ml2 = ProjectionMath.mlfn(phi2, sinphi, cosphi, en);
				if (ml1 == ml2)
					throw new ProjectionException("Eccentricity too close to 1");
				n = (m1 - ProjectionMath.msfn(sinphi, cosphi, es)) / (ml2 - ml1);
				if (n == 0)
					throw new ProjectionException("Invalid value for eccentricity");
			}
			c = ml1 + m1 / n;
			rho0 = c - ProjectionMath.mlfn(projectionLatitude, Math.sin(projectionLatitude),
					Math.cos(projectionLatitude), en);
		} else {
			if (secant)
				n = (cosphi - Math.cos(phi2)) / (phi2 - phi1);
			if (n == 0)
				throw new ProjectionException("Invalid value for lat_1 and lat_2: lat_1 + lat_2 should be > 0");
			c = phi1 + Math.cos(phi1) / n;
			rho0 = c - projectionLatitude;
		}
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double rho = c - (ellips
				? ProjectionMath.mlfn(lpphi, Math.sin(lpphi), Math.cos(lpphi), en)
				: lpphi);
		double lam_mul_n = lplam * n;
		out.x = rho * Math.sin(lam_mul_n);
		out.y = rho0 - rho * Math.cos(lam_mul_n);
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		xyy = rho0 - xyy;
		double rho = ProjectionMath.distance(xyx, xyy);
		if (rho != 0.0) {
			if (n < 0.) {
				rho = -rho;
				xyx = -xyx;
				xyy = -xyy;
			}
			out.y = c - rho;
			if (ellips)
				out.y = ProjectionMath.inv_mlfn(out.y, es, en);
			out.x = Math.atan2(xyx, xyy) / n;
		} else {
			out.x = 0.;
			out.y = n > 0. ? ProjectionMath.HALFPI : -ProjectionMath.HALFPI;
		}
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Equidistant Conic";
	}

}

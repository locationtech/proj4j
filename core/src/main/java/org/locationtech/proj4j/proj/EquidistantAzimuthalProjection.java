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

import org.locationtech.proj4j.geodesic.Geodesic;
import org.locationtech.proj4j.geodesic.GeodesicData;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

public class EquidistantAzimuthalProjection extends AzimuthalProjection {
	
	private final static double TOL = 1.e-8;

	private int mode;
	private double[] en;
	private double M1;
	private double N1;
	private double Mp;
	private double He;
	private double G;
	private double sinphi0, cosphi0;
	private Geodesic geodesic;
	
	public EquidistantAzimuthalProjection() {
		this(Math.toRadians(90.0), Math.toRadians(0.0));
	}

	public EquidistantAzimuthalProjection(double projectionLatitude, double projectionLongitude) {
		super(projectionLatitude, projectionLongitude);
		initialize();
	}
	
	public Object clone() {
		EquidistantAzimuthalProjection p = (EquidistantAzimuthalProjection)super.clone();
		if (en != null)
			p.en = (double[])en.clone();
		return p;
	}
	
	public void initialize() {
		super.initialize();
		if (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) < EPS10) {
			mode = projectionLatitude < 0. ? SOUTH_POLE : NORTH_POLE;
			sinphi0 = projectionLatitude < 0. ? -1. : 1.;
			cosphi0 = 0.;
		} else if (Math.abs(projectionLatitude) < EPS10) {
			mode = EQUATOR;
			sinphi0 = 0.;
			cosphi0 = 1.;
		} else {
			mode = OBLIQUE;
			sinphi0 = Math.sin(projectionLatitude);
			cosphi0 = Math.cos(projectionLatitude);
		}
		if (!spherical) {
			en = ProjectionMath.enfn(es);
			switch (mode) {
			case NORTH_POLE:
				Mp = ProjectionMath.mlfn(ProjectionMath.HALFPI, 1., 0., en);
				break;
			case SOUTH_POLE:
				Mp = ProjectionMath.mlfn(-ProjectionMath.HALFPI, -1., 0., en);
				break;
			case EQUATOR:
			case OBLIQUE:
				N1 = 1. / Math.sqrt(1. - es * sinphi0 * sinphi0);
				G = sinphi0 * (He = e / Math.sqrt(one_es));
				He *= cosphi0;
				geodesic = new Geodesic(this.ellipsoid.getA(), (this.ellipsoid.getA() -
						this.ellipsoid.getB()) / this.ellipsoid.getA());
				break;
			}
		}
	}

	public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
		if (spherical) {
			double  coslam, cosphi, sinphi;

			sinphi = Math.sin(phi);
			cosphi = Math.cos(phi);
			coslam = Math.cos(lam);
			switch (mode) {
			case EQUATOR:
			case OBLIQUE:
				{
					// c is the angular distance to (phi, lam). sin(c) from hypot() and c from
					// atan2() keep the forward accurate near the centre, where acos(cos(c)) lost
					// half the digits and points with cos(c) within 1e-14 of 1 went to the centre
					double s0 = mode == EQUATOR ? 0. : sinphi0, c0 = mode == EQUATOR ? 1. : cosphi0;
					double sinlam = Math.sin(lam);
					double cosc = s0 * sinphi + c0 * cosphi * coslam;
					double t = c0 * sinphi - s0 * cosphi * coslam;
					double sinc = Math.hypot(cosphi * sinlam, t);
					if (cosc < TOL - 1.)
						throw new ProjectionException();
					// c / sin(c), with limit 1 at the centre
					double k = sinc == 0. ? 1. : Math.atan2(sinc, cosc) / sinc;
					xy.x = k * cosphi * sinlam;
					xy.y = k * t;
				}
				break;
			case NORTH_POLE:
				phi = -phi;
				coslam = -coslam;
			case SOUTH_POLE:
				if (Math.abs(phi - ProjectionMath.HALFPI) < EPS10)
					throw new ProjectionException();
				xy.x = (xy.y = (ProjectionMath.HALFPI + phi)) * Math.sin(lam);
				xy.y *= coslam;
				break;
			}
		} else {
			double  coslam, cosphi, sinphi, rho;

			coslam = Math.cos(lam);
			cosphi = Math.cos(phi);
			sinphi = Math.sin(phi);
			switch (mode) {
			case NORTH_POLE:
				coslam = - coslam;
			case SOUTH_POLE:
				xy.x = (rho = Math.abs(Mp - ProjectionMath.mlfn(phi, sinphi, cosphi, en))) *
					Math.sin(lam);
				xy.y = rho * coslam;
				break;
			case EQUATOR:
			case OBLIQUE:
				if (Math.abs(lam) < EPS10 && Math.abs(phi - projectionLatitude) < EPS10) {
					xy.x = xy.y = 0.;
					break;
				}

				GeodesicData g = geodesic.Inverse(
								Math.toDegrees(projectionLatitude),
								Math.toDegrees(projectionLongitude),
								Math.toDegrees(phi),
								Math.toDegrees(lam + projectionLongitude));
				double azi1 = Math.toRadians(g.azi1);
				xy.x = g.s12 * Math.sin(azi1) / geodesic.EquatorialRadius();
				xy.y = g.s12 * Math.cos(azi1) / geodesic.EquatorialRadius();
				break;
			}
		}
		return xy;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		if (spherical) {
			double cosc, c_rh, sinc;

			if ((c_rh = ProjectionMath.distance(x, y)) > Math.PI) {
				if (c_rh - EPS10 > Math.PI)
					throw new ProjectionException(); 
				c_rh = Math.PI;
			} else if (c_rh == 0.) {
				lp.y = projectionLatitude;
				lp.x = 0.;
				return lp;
			}
			if (mode == OBLIQUE || mode == EQUATOR) {
				sinc = Math.sin(c_rh);
				cosc = Math.cos(c_rh);
				// rh * sin(phi), rh * cos(phi) * cos(lam) and rh * cos(phi) * sin(lam): atan2()
				// keeps the latitude accurate near the poles, where asin() of sin(phi) did not
				double s0 = mode == EQUATOR ? 0. : sinphi0, c0 = mode == EQUATOR ? 1. : cosphi0;
				double n = c_rh * cosc * s0 + y * sinc * c0;
				y = c_rh * cosc * c0 - y * sinc * s0;
				x *= sinc;
				lp.y = Math.atan2(n, Math.sqrt(x * x + y * y));
				// no special case for y == 0: the longitude is +/-90 degrees there, not 0
				lp.x = Math.atan2(x, y);
			} else if (mode == NORTH_POLE) {
				lp.y = ProjectionMath.HALFPI - c_rh;
				lp.x = Math.atan2(x, -y);
			} else {
				lp.y = c_rh - ProjectionMath.HALFPI;
				lp.x = Math.atan2(x, y);
			}
		} else {
			double c;

			if ((c = ProjectionMath.distance(x, y)) < EPS10) {
				lp.y = projectionLatitude;
				lp.x = 0.;
				return (lp);
			}
			if (mode == OBLIQUE || mode == EQUATOR) {
				double x2 = x * geodesic.EquatorialRadius();
				double y2 = y * geodesic.EquatorialRadius();
				double azi1 = Math.atan2(x2, y2);
				double s12 = Math.sqrt(x2 * x2 + y2 * y2);
				GeodesicData g = geodesic.Direct(
								Math.toDegrees(projectionLatitude),
								Math.toDegrees(projectionLongitude),
								Math.toDegrees(azi1),
								s12);
				lp.y = Math.toRadians(g.lat2);
				lp.x = Math.toRadians(g.lon2);
				lp.x -= projectionLongitude;
			} else {
				lp.y = ProjectionMath.inv_mlfn(mode == NORTH_POLE ? Mp - c : Mp + c, es, en);
				lp.x = Math.atan2(x, mode == NORTH_POLE ? -y : y);
			}
		}
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Equidistant Azimuthal";
	}
	
}


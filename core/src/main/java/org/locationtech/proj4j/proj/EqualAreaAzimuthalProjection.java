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

/**
 * Lambert equal-area azimuthal. Not registered under any proj name — {@code +proj=laea} maps to
 * {@link LambertAzimuthalEqualAreaProjection} — but it is public API and it implements the same
 * algorithm, so it tracks {@code 9.8.1:src/projections/laea.cpp} for the same reasons.
 *
 * <p>{@code ProjectionMath.authset}/{@code authlat}/{@code qsfn} are replaced by
 * {@link AuthalicLat}, the order-6 port of {@code 9.8.1:src/latitudes.cpp}. The third-order series
 * they implement is up to <b>2.211 mm</b> off at latitude 18.01 degrees on GRS80, 22 times the
 * 0.1 mm bar this family's conformance rows set.
 *
 * <p>The forward direction also changes shape, following {@code laea.cpp:39-46} and
 * {@code laea.cpp:283-285}: the authalic latitude {@code xi} comes from the direct
 * {@code phi -> xi} series and {@code q} is recovered as {@code sin(xi) * qp}, instead of computing
 * {@code q} first and then {@code sinb = q/qp}, {@code cosb = sqrt(1 - sinb^2)}. Upstream stopped
 * using the {@code asin} form because it loses relative accuracy near the poles. {@code cea} and
 * {@code aea} keep raw {@code q} in their forwards — see {@code cea.cpp:21}, {@code aea.cpp:70} —
 * so this is a per-projection decision, not a blanket rule.
 */
public class EqualAreaAzimuthalProjection extends AzimuthalProjection {

	private static final long serialVersionUID = -8002324637336088184L;

	private double sinb1;
	private double cosb1;
	private double xmf;
	private double ymf;
	private double mmf;
	private double qp;
	private double dd;
	private double rq;

	/**
	 * The order-6 authalic-latitude machinery. Built once per CRS in {@link #initialize()}:
	 * its coefficient tables depend only on the ellipsoid, and precomputing them is the point.
	 * Immutable, {@link java.io.Serializable} and safe to share across the clones this class
	 * hands out.
	 */
	private AuthalicLat authalic;

	public EqualAreaAzimuthalProjection() {
		initialize();
	}

	/**
	 * Retained for source compatibility. The array that used to need deep-copying here is gone;
	 * {@link AuthalicLat} is immutable, so sharing the reference with the clone is correct.
	 */
	public Object clone() {
		return super.clone();
	}

	public void initialize() {
		super.initialize();
		if (spherical) {
			if (mode == OBLIQUE) {
				sinphi0 = Math.sin(projectionLatitude);
				cosphi0 = Math.cos(projectionLatitude);
			}
		} else {
			double sinphi;

			authalic = new AuthalicLat(es);
			qp = authalic.qp();
			mmf = .5 / (1. - es);
			switch (mode) {
			case NORTH_POLE:
			case SOUTH_POLE:
				dd = 1.;
				break;
			case EQUATOR:
				dd = 1. / (rq = Math.sqrt(.5 * qp));
				xmf = 1.;
				ymf = .5 * qp;
				break;
			case OBLIQUE:
				rq = Math.sqrt(.5 * qp);
				sinphi = Math.sin(projectionLatitude);
				// laea.cpp:283-285: the authalic latitude of lat_0 through the direct series,
				// then its sine and cosine, rather than sinb1 = q/qp and sqrt(1 - sinb1^2).
				double b1 = authalic.forward(projectionLatitude, sinphi, Math.cos(projectionLatitude));
				sinb1 = Math.sin(b1);
				cosb1 = Math.cos(b1);
				dd = Math.cos(projectionLatitude) / (Math.sqrt(1. - es * sinphi * sinphi) *
				   rq * cosb1);
				ymf = (xmf = rq) / dd;
				xmf *= dd;
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
				xy.y = 1. + cosphi * coslam;
				if (xy.y <= EPS10) throw new ProjectionException();
				xy.x = (xy.y = Math.sqrt(2. / xy.y)) * cosphi * Math.sin(lam);
				xy.y *= mode == EQUATOR ? sinphi :
				   cosphi0 * sinphi - sinphi0 * cosphi * coslam;
				break;
			case OBLIQUE:
				xy.y = 1. + sinphi0 * sinphi + cosphi0 * cosphi * coslam;
				if (xy.y <= EPS10) throw new ProjectionException();
				xy.x = (xy.y = Math.sqrt(2. / xy.y)) * cosphi * Math.sin(lam);
				xy.y *= mode == EQUATOR ? sinphi :
				   cosphi0 * sinphi - sinphi0 * cosphi * coslam;
				break;
			case NORTH_POLE:
				coslam = -coslam;
			case SOUTH_POLE:
				if (Math.abs(phi + projectionLatitude) < EPS10) throw new ProjectionException();
				xy.y = ProjectionMath.QUARTERPI - phi * .5;
				xy.y = 2. * (mode == SOUTH_POLE ? Math.cos(xy.y) : Math.sin(xy.y));
				xy.x = xy.y * Math.sin(lam);
				xy.y *= coslam;
				break;
			}
		} else {
			double coslam, sinlam, sinphi, q, sinb = 0, cosb = 0, b = 0;

			coslam = Math.cos(lam);
			sinlam = Math.sin(lam);
			sinphi = Math.sin(phi);
			// laea.cpp:39-46: xi from the direct phi -> xi series, q recovered as sin(xi)*qp.
			double xi = authalic.forward(phi, sinphi, Math.cos(phi));
			q = Math.sin(xi) * qp;
			if (mode == OBLIQUE || mode == EQUATOR) {
				sinb = Math.sin(xi);
				cosb = Math.cos(xi);
			}
			switch (mode) {
			case OBLIQUE:
				b = 1. + sinb1 * sinb + cosb1 * cosb * coslam;
				break;
			case EQUATOR:
				b = 1. + cosb * coslam;
				break;
			case NORTH_POLE:
				b = ProjectionMath.HALFPI + phi;
				q = qp - q;
				break;
			case SOUTH_POLE:
				b = phi - ProjectionMath.HALFPI;
				q = qp + q;
				break;
			}
			if (Math.abs(b) < EPS10) throw new ProjectionException();
			switch (mode) {
			case OBLIQUE:
				xy.y = ymf * ( b = Math.sqrt(2. / b) )
				   * (cosb1 * sinb - sinb1 * cosb * coslam);
				xy.x = xmf * b * cosb * sinlam;
				break;
			case EQUATOR:
				xy.y = (b = Math.sqrt(2. / (1. + cosb * coslam))) * sinb * ymf; 
				xy.x = xmf * b * cosb * sinlam;
				break;
			case NORTH_POLE:
			case SOUTH_POLE:
				// laea.cpp:75 uses 1e-15, not 0. Now that q comes from sin(xi)*qp rather than
				// the exact-q formula, `qp - q` at the pole is rounding noise rather than an
				// exact zero, and sqrt of a denormal would place the point sub-millimetres off
				// the origin instead of on it.
				if (q >= 1e-15) {
					xy.x = (b = Math.sqrt(q)) * sinlam;
					xy.y = coslam * (mode == SOUTH_POLE ? b : -b);
				} else
					xy.x = xy.y = 0.;
				break;
			}
		}
		return xy;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		if (spherical) {
			double  cosz = 0, rh, sinz = 0;

			rh = ProjectionMath.distance(x, y);
			if ((lp.y = rh * .5 ) > 1.) throw new ProjectionException();
			lp.y = 2. * Math.asin(lp.y);
			if (mode == OBLIQUE || mode == EQUATOR) {
				sinz = Math.sin(lp.y);
				cosz = Math.cos(lp.y);
			}
			switch (mode) {
			case EQUATOR:
				lp.y = Math.abs(rh) <= EPS10 ? 0. : Math.asin(y * sinz / rh);
				x *= sinz;
				y = cosz * rh;
				break;
			case OBLIQUE:
				lp.y = Math.abs(rh) <= EPS10 ? projectionLatitude :
				   Math.asin(cosz * sinphi0 + y * sinz * cosphi0 / rh);
				x *= sinz * cosphi0;
				y = (cosz - Math.sin(lp.y) * sinphi0) * rh;
				break;
			case NORTH_POLE:
				y = -y;
				lp.y = ProjectionMath.HALFPI - lp.y;
				break;
			case SOUTH_POLE:
				lp.y -= ProjectionMath.HALFPI;
				break;
			}
			lp.x = (y == 0. && (mode == EQUATOR || mode == OBLIQUE)) ?
				0. : Math.atan2(x, y);
		} else {
			double cCe, sCe, q, rho, ab = 0;

			switch (mode) {
			case EQUATOR:
			case OBLIQUE:
				if ((rho = ProjectionMath.distance(x /= dd, y *=  dd)) < EPS10) {
					lp.x = 0.;
					lp.y = projectionLatitude;
					return (lp);
				}
				cCe = Math.cos(sCe = 2. * Math.asin(.5 * rho / rq));
				x *= (sCe = Math.sin(sCe));
				if (mode == OBLIQUE) {
					q = qp * (ab = cCe * sinb1 + y * sCe * cosb1 / rho);
					y = rho * cosb1 * cCe - y * sinb1 * sCe;
				} else {
					q = qp * (ab = y * sCe / rho);
					y = rho * cCe;
				}
				break;
			case NORTH_POLE:
				y = -y;
			case SOUTH_POLE:
				if ((q = (x * x + y * y)) == 0) {
					lp.x = 0.;
					lp.y = projectionLatitude;
					return lp;
				}
				ab = 1. - q / qp;
				if (mode == SOUTH_POLE)
					ab = - ab;
				break;
			}
			lp.x = Math.atan2(x, y);
			lp.y = authalic.inverse(Math.asin(ab));
		}
		return lp;
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
		return "Lambert Equal Area Azimuthal";
	}

}

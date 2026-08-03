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

import java.util.Objects;

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.ProjectionMath;

/**
* Oblique Mercator Projection algorithm is taken from the USGS PROJ package.
*/
public class ObliqueMercatorProjection extends CylindricalProjection {

	private final static double TOL	= 1.0e-7;

	private double lamc, lam1, phi1, lam2, phi2, Gamma, al, bl, el, singam, cosgam, sinrot, cosrot, u_0;
	private boolean ellips, rot, no_uoff;
	/**
	 * The raw {@code +alpha} and {@code +gamma}, NaN when not given. They decide which of PROJ's
	 * three setups runs, so they must survive {@link #initialize()} - which can be called more
	 * than once - untouched by the derived azimuth and rectified bearing.
	 */
	private double alphaParam = Double.NaN, gammaParam = Double.NaN;

	public ObliqueMercatorProjection() {
		ellipsoid = Ellipsoid.WGS84;
		projectionLatitude = Math.toRadians(0);
		projectionLongitude = Math.toRadians(0);
		minLongitude = Math.toRadians(-60);
		maxLongitude = Math.toRadians(60);
		minLatitude = Math.toRadians(-80);
		maxLatitude = Math.toRadians(80);
	}

	/**
	* Set up a projection suitable for State Plane Coordinates.
	*/
	public ObliqueMercatorProjection(Ellipsoid ellipsoid, double lon_0, double lat_0, double alpha, double k, double x_0, double y_0) {
		setEllipsoid(ellipsoid);
		lonc = lon_0;
		projectionLatitude = lat_0;
		setAlpha(alpha);
		scaleFactor = k;
		falseEasting = x_0;
		falseNorthing = y_0;
		initialize();
	}

	@Override public void setAlpha(double alpha) {
		super.setAlpha(alpha);
		alphaParam = this.alpha;
	}

	@Override public void setAlphaDegrees(double alpha) {
		super.setAlphaDegrees(alpha);
		alphaParam = this.alpha;
	}

	public void initialize() {
		super.initialize();
		double con, com, cosphi0, d, f, h, l, sinphi0, p, j, gamma0;

		rot = true;
		lamc = lonc;

		// which setup applies is decided by the raw parameters, exactly as in PROJ
		boolean azi = !Double.isNaN(alphaParam);   // +alpha given
		boolean gzi = !Double.isNaN(gammaParam);   // +gamma given
		double alpha_c = azi ? alphaParam : 0.;
		double gamma = gzi ? gammaParam : 0.;

		if (azi || gzi) {
			if (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) <= TOL)
				throw new ProjectionException("Invalid value for lat_0: |lat_0| should be < 90");
			if (azi && (Math.abs(alpha_c) <= TOL ||
					Math.abs(Math.abs(alpha_c) - ProjectionMath.HALFPI) <= TOL))
				throw new ProjectionException(
					"Invalid value for alpha: it should be different from 0 and |alpha| < 90");
		} else {
			// two-point form: +lat_1/+lon_1/+lat_2/+lon_2
			phi1 = projectionLatitude1;
			phi2 = projectionLatitude2;
			lam1 = projectionLongitude1;
			lam2 = projectionLongitude2;
			if (Math.abs(phi1) > ProjectionMath.HALFPI - TOL)
				throw new ProjectionException("Invalid value for lat_1: |lat_1| should be < 90");
			if (Math.abs(phi2) > ProjectionMath.HALFPI - TOL)
				throw new ProjectionException("Invalid value for lat_2: |lat_2| should be < 90");
			if (Math.abs(phi1 - phi2) <= TOL)
				throw new ProjectionException(
					"Invalid value for lat_1/lat_2: lat_1 should be different from lat_2");
			if (Math.abs(phi1) <= TOL)
				throw new ProjectionException("Invalid value for lat_1: lat_1 should be different from 0");
			if (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) <= TOL)
				throw new ProjectionException("Invalid value for lat_0: |lat_0| should be < 90");
		}
		com = (spherical = es == 0.) ? 1 : Math.sqrt(one_es);
		if (Math.abs(projectionLatitude) > EPS10) {
			sinphi0 = Math.sin(projectionLatitude);
			cosphi0 = Math.cos(projectionLatitude);
			if (!spherical) {
				con = 1. - es * sinphi0 * sinphi0;
				bl = cosphi0 * cosphi0;
				bl = Math.sqrt(1. + es * bl * bl / one_es);
				al = bl * scaleFactor * com / con;
				d = bl * com / (cosphi0 * Math.sqrt(con));
			} else {
				bl = 1.;
				al = scaleFactor;
				d = 1. / cosphi0;
			}
			if ((f = d * d - 1.) <= 0.)
				f = 0.;
			else {
				f = Math.sqrt(f);
				if (projectionLatitude < 0.)
					f = -f;
			}
			el = f += d;
			if (!spherical)
				el *= Math.pow(ProjectionMath.tsfn(projectionLatitude, sinphi0, e), bl);
			else
				el *= Math.tan(.5 * (ProjectionMath.HALFPI - projectionLatitude));
		} else {
			bl = 1. / com;
			al = scaleFactor;
			el = d = f = 1.;
		}
		if (azi || gzi) {
			if (azi) {
				gamma0 = Math.asin(Math.sin(alpha_c) / d);
				if (!gzi)
					gamma = alpha_c;
			} else {
				gamma0 = gamma;
				alpha_c = Math.asin(d * Math.sin(gamma0));
				if (Double.isNaN(alpha_c))
					throw new ProjectionException("Invalid value for gamma: given lat_0, |gamma| should be <= "
						+ Math.toDegrees(Math.asin(1. / d)));
			}
			projectionLongitude = lamc - Math.asin((.5 * (f - 1. / f)) *
					   Math.tan(gamma0)) / bl;
		} else {
			if (!spherical) {
				h = Math.pow(ProjectionMath.tsfn(phi1, Math.sin(phi1), e), bl);
				l = Math.pow(ProjectionMath.tsfn(phi2, Math.sin(phi2), e), bl);
			} else {
				h = Math.tan(.5 * (ProjectionMath.HALFPI - phi1));
				l = Math.tan(.5 * (ProjectionMath.HALFPI - phi2));
			}
			f = el / h;
			p = (l - h) / (l + h);
			j = el * el;
			j = (j - l * h) / (j + l * h);
			if ((con = lam1 - lam2) < -Math.PI)
				lam2 -= ProjectionMath.TWOPI;
			else if (con > Math.PI)
				lam2 += ProjectionMath.TWOPI;
			projectionLongitude = ProjectionMath.normalizeLongitude(.5 * (lam1 + lam2) - Math.atan(
			   j * Math.tan(.5 * bl * (lam1 - lam2)) / p) / bl);
			gamma0 = Math.atan(2. * Math.sin(bl * ProjectionMath.normalizeLongitude(lam1 - projectionLongitude)) /
			   (f - 1. / f));
			gamma = alpha_c = Math.asin(d * Math.sin(gamma0));
		}
		singam = Math.sin(gamma0);
		cosgam = Math.cos(gamma0);
		sinrot = Math.sin(gamma);
		cosrot = Math.cos(gamma);

		// The natural-origin offset uses the azimuth of the central line (alpha), following Snyder's
		// Hotine Oblique Mercator: u_c = (A / B) * atan(sqrt(D^2 - 1) / cos(alpha_c)). Using the
		// rectified bearing (gamma) here misplaces the projection centre whenever gamma != alpha,
		// e.g. for definitions with an explicit "+gamma=0" and a non-zero azimuth.
		if (no_uoff)
			u_0 = 0.;
		else {
			u_0 = Math.abs(al * Math.atan(Math.sqrt(d * d - 1.) / Math.cos(alpha_c)) / bl);
			if (projectionLatitude < 0.)
				u_0 = - u_0;
		}

		// publish the derived azimuth and rectified bearing, as PROJ keeps alpha_c/gamma in its state
		this.alpha = alpha_c;
		this.Gamma = gamma;
	}

    @Override public void setGamma(double gamma) {
        this.gammaParam = gamma;
    }

    /** {@code +lon_1}, the longitude of the first point of the two-point form. */
    public void setProjectionLongitude1(double lon1) {
        this.projectionLongitude1 = lon1;
    }

    public void setProjectionLongitude1Degrees(double lon1) {
        this.projectionLongitude1 = DTR * lon1;
    }

    /** {@code +lon_2}, the longitude of the second point of the two-point form. */
    public void setProjectionLongitude2(double lon2) {
        this.projectionLongitude2 = lon2;
    }

    public void setProjectionLongitude2Degrees(double lon2) {
        this.projectionLongitude2 = DTR * lon2;
    }
    
    @Override public void setNoUoff(boolean no_uoff) {
    	this.no_uoff = no_uoff;
    }

	public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
		double con, q, s, ul, us, vl, vs;

		vl = Math.sin(bl * lam);
		if (Math.abs(Math.abs(phi) - ProjectionMath.HALFPI) <= EPS10) {
			ul = phi < 0. ? -singam : singam;
			us = al * phi / bl;
		} else {
			q = el / (!spherical ? Math.pow(ProjectionMath.tsfn(phi, Math.sin(phi), e), bl)
				: Math.tan(.5 * (ProjectionMath.HALFPI - phi)));
			s = .5 * (q - 1. / q);
			ul = 2. * (s * singam - vl * cosgam) / (q + 1. / q);
			con = Math.cos(bl * lam);
			if (Math.abs(con) >= TOL) {
				// atan2 picks the right quadrant on its own; the historical
				// atan(y/con) + PI correction is only right for y > 0
				us = al * Math.atan2(s * cosgam + vl * singam, con) / bl;
			} else
				us = al * lam;
		}
		if (Math.abs(Math.abs(ul) - 1.) <= EPS10) throw new ProjectionException("Obl 3");
		vs = .5 * al * Math.log((1. - ul) / (1. + ul)) / bl;
		us -= u_0;
		if (!rot) {
			xy.x = us;
			xy.y = vs;
		} else {
			xy.x = vs * cosrot + us * sinrot;
			xy.y = us * cosrot - vs * sinrot;
		}
		return xy;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		double q, s, ul, us, vl, vs;

		if (! rot) {
			us = x;
			vs = y;
		} else {
			vs = x * cosrot - y * sinrot;
			us = y * cosrot + x * sinrot;
		}
		us += u_0;
		q = Math.exp(- bl * vs / al);
		s = .5 * (q - 1. / q);
		vl = Math.sin(bl * us / al);
		ul = 2. * (vl * cosgam + s * singam) / (q + 1. / q);
		if (Math.abs(Math.abs(ul) - 1.) < EPS10) {
			lp.x = 0.;
			lp.y = ul < 0. ? -ProjectionMath.HALFPI : ProjectionMath.HALFPI;
		} else {
			lp.y = el / Math.sqrt((1. + ul) / (1. - ul));
			if (!spherical) {
				lp.y = ProjectionMath.phi2(Math.pow(lp.y, 1. / bl), e);
			} else
				lp.y = ProjectionMath.HALFPI - 2. * Math.atan(lp.y);
			lp.x = - Math.atan2((s * cosgam -
				vl * singam), Math.cos(bl * us / al)) / bl;
		}
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Oblique Mercator";
	}

	@Override
	public boolean equals(Object that) {
			if (this == that) {
					return true;
			}
			if (that instanceof ObliqueMercatorProjection) {
					ObliqueMercatorProjection p = (ObliqueMercatorProjection) that;
					return (
						Gamma == p.Gamma &&
						alpha == p.alpha &&
						lonc == p.lonc &&
						super.equals(that));
			}
			return false;
	}

	@Override
	public int hashCode() {
			return Objects.hash(Gamma, alpha, lonc, super.hashCode());
	}
}

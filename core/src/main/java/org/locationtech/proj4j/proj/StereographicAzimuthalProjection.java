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

import org.locationtech.proj4j.*;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Stereographic azimuthal projection, {@code 9.8.1:src/projections/stere.cpp}.
 *
 * <h2>One deliberate divergence from PROJ 9.8.1 — do not "fix" it back</h2>
 *
 * The ellipsoidal inverse here calls {@link ConformalLat#phi2} where upstream
 * ({@code stere.cpp:173-188}) still runs its own {@code NITER = 8}, {@code CONV = 1e-10}
 * Newton loop with a {@code pow} on every trip. <b>The two solve the same fixed point.</b>
 * Upstream iterates
 * <pre>
 *   phi = 2 atan(tp * ((1 + e sin phi)/(1 - e sin phi))^(halfe)) - halfpi
 * </pre>
 * with {@code halfpi = +pi/2, halfe = +e/2} for the oblique and equatorial aspects and
 * {@code halfpi = -pi/2, halfe = -e/2} for the polar ones. Rearranged, both branches are
 * exactly {@code tan(pi/4 - phi/2) = ts * ((1 - e sin phi)/(1 + e sin phi))^(e/2)}, which is
 * the fixed point of {@code pj_phi2}, with
 * <ul>
 * <li><b>{@code ts = 1/tp}</b> for oblique/equatorial, where
 *     {@code tp = tan(pi/4 + phi_l/2)}; and
 * <li><b>{@code ts = -tp}</b> for the poles, where {@code tp = -rho/akm1}, i.e.
 *     {@code ts = rho/akm1}.
 * </ul>
 * Upstream simply never refactored it. The Karney formulation is better on every axis —
 * one or two iterations instead of up to eight, no {@code pow} in the loop, a fixed trip
 * count (hence a platform-independent answer), and about 2 nm of error against upstream's
 * roughly 4 um. It therefore makes proj4j differ from 9.8.1 by up to <b>~4 um</b> on
 * {@code stere} inverses — 25,000 times inside the 0.1 mm bar the corpus sets for this
 * projection.
 */
public class StereographicAzimuthalProjection extends AzimuthalProjection {

	private static final long serialVersionUID = 4780435696828185438L;

	private final static double TOL = 1.e-8;

	private double akm1;

	/**
	 * A bare {@code +proj=stere}, i.e. the equatorial aspect with {@code +lat_ts} absent.
	 * <p>
	 * <b>Two defaults are fixed here, and both were wrong in the same way</b> — the class field
	 * was standing in for a parameter PROJ defaults independently, and {@code Proj4Parser} assigns
	 * a keyword <em>only when it is present</em>, so the class default <em>is</em> the effective
	 * default.
	 * <ul>
	 * <li><b>{@code lat_0} was 90&deg;</b>, so {@code +proj=stere +ellps=GRS80} ran the polar
	 *     aspect where PROJ runs the equatorial one. PROJ's {@code pj_init} reads {@code "rlat_0"}
	 *     and gets 0.</li>
	 * <li><b>{@code lat_ts} was 0</b>, but {@code 9.8.1:stere.cpp:305-309} reads it as
	 *     {@code pj_param(...,"tlat_ts").i ? |lat_ts| : M_HALFPI} &mdash; <em>&pi;/2 when the
	 *     keyword is absent</em>. With 0 the two polar branches take their
	 *     {@code cos(phits)/tsfn(phits)} arm instead of {@code 2*k_0/sqrt((1+e)^(1+e)(1-e)^(1-e))},
	 *     which scaled {@code +proj=stere +ellps=GRS80 +lat_0=-90 +k_0=0.97} by a factor of
	 *     1.9335 &mdash; 1,056 km at the corpus's test point.</li>
	 * </ul>
	 * {@code +lat_ts=0} on a polar aspect is legal upstream and still reaches the other arm,
	 * because the parser then writes the 0 explicitly.
	 */
	public StereographicAzimuthalProjection() {
		this(0.0, 0.0);
	}

	public StereographicAzimuthalProjection(double projectionLatitude, double projectionLongitude) {
		super(projectionLatitude, projectionLongitude);
		// stere.cpp:305-309 -- phits defaults to pi/2, not to 0, and it is read for every aspect.
		trueScaleLatitude = ProjectionMath.HALFPI;
		initialize();
	}

	public void setupUPS(int pole) {
		projectionLatitude = (pole == SOUTH_POLE) ? -ProjectionMath.HALFPI: ProjectionMath.HALFPI;
		projectionLongitude = 0.0;
		scaleFactor = 0.994;
		falseEasting = 2000000.0;
		falseNorthing = 2000000.0;
		trueScaleLatitude = ProjectionMath.HALFPI;
		initialize();
	}

	public void initialize() {
		double t;

		super.initialize();
		if (Math.abs((t = Math.abs(projectionLatitude)) - ProjectionMath.HALFPI) < EPS10)
			mode = projectionLatitude < 0. ? SOUTH_POLE : NORTH_POLE;
		else
			mode = t > EPS10 ? OBLIQUE : EQUATOR;
		trueScaleLatitude = Math.abs(trueScaleLatitude);
		if (! spherical) {
			double X;

			switch (mode) {
			case NORTH_POLE:
			case SOUTH_POLE:
				if (Math.abs(trueScaleLatitude - ProjectionMath.HALFPI) < EPS10)
					akm1 = 2. * scaleFactor /
					   Math.sqrt(Math.pow(1+e,1+e)*Math.pow(1-e,1-e));
				else {
					akm1 = Math.cos(trueScaleLatitude) /
					   ConformalLat.tsfn(trueScaleLatitude, t = Math.sin(trueScaleLatitude), e);
					t *= e;
					akm1 /= Math.sqrt(1. - t * t);
				}
				break;
			// 9.8.1:stere.cpp:262-270 handles EQUIT and OBLIQ in ONE branch, so the
			// equatorial aspect also gets sinX1 = 0, cosX1 = 1. proj4j split them and
			// left the equatorial case with sinphi0 = cosphi0 = 0, which made the whole
			// ellipsoidal equatorial *inverse* collapse to (0, 0) for every input --
			// tp = 2*atan2(rho*cosphi0, akm1) = 0, so ts = 1 and phi2(1) = 0.
			// For phi0 = 0 the shared formula still yields akm1 = 2*k0, so the forward
			// direction is unchanged.
			case EQUATOR:
			case OBLIQUE:
				t = Math.sin(projectionLatitude);
				X = 2. * Math.atan(ssfn(projectionLatitude, t, e)) - ProjectionMath.HALFPI;
				t *= e;
				akm1 = 2. * scaleFactor * Math.cos(projectionLatitude) / Math.sqrt(1. - t * t);
				sinphi0 = Math.sin(X);
				cosphi0 = Math.cos(X);
				break;
			}
		} else {
			switch (mode) {
			case OBLIQUE:
				sinphi0 = Math.sin(projectionLatitude);
				cosphi0 = Math.cos(projectionLatitude);
			case EQUATOR:
				akm1 = 2. * scaleFactor;
				break;
			case SOUTH_POLE:
			case NORTH_POLE:
				akm1 = Math.abs(trueScaleLatitude - ProjectionMath.HALFPI) >= EPS10 ?
				   Math.cos(trueScaleLatitude) / Math.tan(ProjectionMath.QUARTERPI - .5 * trueScaleLatitude) :
				   2. * scaleFactor ;
				break;
			}
		}
	}

	public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
		double coslam = Math.cos(lam);
		double sinlam = Math.sin(lam);
		double sinphi = Math.sin(phi);

		if (spherical) {
			double cosphi = Math.cos(phi);

			switch (mode) {
			case EQUATOR:
				xy.y = 1. + cosphi * coslam;
				if (xy.y <= EPS10)
					throw new ProjectionException();
				xy.x = (xy.y = akm1 / xy.y) * cosphi * sinlam;
				xy.y *= sinphi;
				break;
			case OBLIQUE:
				xy.y = 1. + sinphi0 * sinphi + cosphi0 * cosphi * coslam;
				if (xy.y <= EPS10)
					throw new ProjectionException();
				xy.x = (xy.y = akm1 / xy.y) * cosphi * sinlam;
				xy.y *= cosphi0 * sinphi - sinphi0 * cosphi * coslam;
				break;
			case NORTH_POLE:
				coslam = - coslam;
				phi = - phi;
			case SOUTH_POLE:
				if (Math.abs(phi - ProjectionMath.HALFPI) < TOL)
					throw new ProjectionException();
				xy.x = sinlam * ( xy.y = akm1 * Math.tan(ProjectionMath.QUARTERPI + .5 * phi) );
				xy.y *= coslam;
				break;
			}
		} else {
			double sinX = 0, cosX = 0, X, A;

			if (mode == OBLIQUE || mode == EQUATOR) {
				sinX = Math.sin(X = 2. * Math.atan(ssfn(phi, sinphi, e)) - ProjectionMath.HALFPI);
				cosX = Math.cos(X);
			}
			switch (mode) {
			case OBLIQUE:
				A = akm1 / (cosphi0 * (1. + sinphi0 * sinX + cosphi0 * cosX * coslam));
				xy.y = A * (cosphi0 * sinX - sinphi0 * cosX * coslam);
				xy.x = A * cosX;
				break;
			case EQUATOR:
				// https://github.com/OSGeo/PROJ/blob/8.0.0/src/projections/stere.cpp#L77
				A = akm1 / (1. + cosX * coslam);
				xy.y = A * sinX;
				xy.x = A * cosX;
				break;
			case SOUTH_POLE:
				phi = -phi;
				coslam = -coslam;
				sinphi = -sinphi;
			case NORTH_POLE:
				// 9.8.1:stere.cpp:83-86. Exactly at the pole ConformalLat.tsfn returns
				// cos(pi/2)/2 = 3.06e-17 rather than the 0 that the old tan-based tsfn
				// produced by accident; upstream special-cases it, and the corpus asserts
				// `+proj=stere +lat_0=90 +lat_ts=70; accept 0 90; expect 0 0` at
				// tolerance 1e-15 m.
				if (Math.abs(phi - ProjectionMath.HALFPI) < 1e-15)
					xy.x = 0;
				else
					xy.x = akm1 * ConformalLat.tsfn(phi, sinphi, e);
				xy.y = - xy.x * coslam;
				break;
			}
			xy.x = xy.x * sinlam;
		}
		return xy;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		if (spherical) {
			double  c, rh, sinc, cosc;

			sinc = Math.sin(c = 2. * Math.atan((rh = ProjectionMath.distance(x, y)) / akm1));
			cosc = Math.cos(c);
			lp.x = 0.;
			switch (mode) {
			case EQUATOR:
				if (Math.abs(rh) <= EPS10)
					lp.y = 0.;
				else
					lp.y = Math.asin(y * sinc / rh);
				if (cosc != 0. || x != 0.)
					lp.x = Math.atan2(x * sinc, cosc * rh);
				break;
			case OBLIQUE:
				if (Math.abs(rh) <= EPS10)
					lp.y = projectionLatitude;
				else
					lp.y = Math.asin(cosc * sinphi0 + y * sinc * cosphi0 / rh);
				if ((c = cosc - sinphi0 * Math.sin(lp.y)) != 0. || x != 0.)
					lp.x = Math.atan2(x * sinc * cosphi0, c * rh);
				break;
			case NORTH_POLE:
				y = -y;
			case SOUTH_POLE:
				if (Math.abs(rh) <= EPS10)
					lp.y = projectionLatitude;
				else
					lp.y = Math.asin(mode == SOUTH_POLE ? - cosc : cosc);
				lp.x = (x == 0. && y == 0.) ? 0. : Math.atan2(x, y);
				break;
			}
		} else {
			double cosphi, sinphi, ts, phi_l, rho;

			rho = ProjectionMath.distance(x, y);
			switch (mode) {
			case OBLIQUE:
			case EQUATOR:
			default:	// To prevent the compiler complaining about uninitialized vars.
				double tp;
				cosphi = Math.cos( tp = 2. * Math.atan2(rho * cosphi0 , akm1) );
				sinphi = Math.sin(tp);
				if (rho <= 0) {
				  phi_l = Math.asin(cosphi * sinphi0);
				}
				else {
				  phi_l = Math.asin(cosphi * sinphi0 + (y * sinphi * cosphi0 / rho));
				}
				// ts = 1 / tan(pi/4 + phi_l/2) -- see the class comment on the divergence.
				ts = 1. / Math.tan(.5 * (ProjectionMath.HALFPI + phi_l));
				x *= sinphi;
				y = rho * cosphi0 * cosphi - y * sinphi0* sinphi;
				break;
			case NORTH_POLE:
				y = -y;
			case SOUTH_POLE:
				// ts = -tp with tp = -rho/akm1.
				ts = rho / akm1;
				break;
			}
			lp.y = ConformalLat.phi2(ts, e);
			if (mode == SOUTH_POLE)
				lp.y = -lp.y;
			lp.x = (x == 0. && y == 0.) ? 0. : Math.atan2(x, y);
		}
		return lp;
	}

	/**
	 * Returns true if this projection is conformal
	 */
	public boolean isConformal() {
		return true;
	}

	public boolean hasInverse() {
		return true;
	}

	private double ssfn(double phit, double sinphi, double eccen) {
		sinphi *= eccen;
		return Math.tan (.5 * (ProjectionMath.HALFPI + phit)) *
		   Math.pow((1. - sinphi) / (1. + sinphi), .5 * eccen);
	}

	public String toString() {
		return "Stereographic Azimuthal";
	}
}
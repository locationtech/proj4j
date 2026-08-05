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
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Azimuthal equidistant, {@code 9.8.1:src/projections/aeqd.cpp}.
 *
 * <p>Only the two polar aspects are meridian-arc arithmetic — the equatorial and oblique ellipsoidal
 * aspects go through the geodesic solver — so {@link MeridianArc} replaces
 * {@code ProjectionMath.enfn}/{@code mlfn}/{@code inv_mlfn} at exactly three sites:
 * {@code Mp} at initialisation ({@code aeqd.cpp:98} and the {@code S_POLE} twin), the polar forward
 * ({@code aeqd.cpp:99}) and the polar inverse ({@code aeqd.cpp:322}).
 *
 * <p>The polar rows the corpus sets, {@code builtins.gie:216-253}, are at {@code tolerance 0.1 m}
 * and Snyder's table 31 is quoted only to 0.1 m, so they cannot resolve this change; the improvement
 * is a forward meridian arc that goes from 4.9 um out at latitude 72.5 degrees to under 1 nm against
 * an independent quadrature, and an inverse that is closed form instead of a ten-step Newton loop
 * with a data-dependent — hence platform-dependent — trip count.
 */
public class EquidistantAzimuthalProjection extends AzimuthalProjection {

	private static final long serialVersionUID = 675511536424237220L;

	/**
	 * {@code aeqd.cpp:57}, {@code #define TOL 1.e-14}.
	 * <p>
	 * <b>Was {@code 1.e-8}</b>, six orders of magnitude too loose, which put a dead zone of
	 * radius {@code acos(1 - 1e-8) = 1.41e-4} rad &mdash; about <b>900 m</b> on the Earth
	 * &mdash; around the centre of every spherical oblique or equatorial {@code aeqd}, inside
	 * which the forward returned exactly {@code (0, 0)}. It also widened the antipodal
	 * rejection by the same factor. {@code builtins.gie:163} samples a point 0.147 m from the
	 * centre and expects {@code (-0.096, 0.111)}.
	 */
	private final static double TOL = 1.e-14;

	private int mode;

	/**
	 * The 6th-order meridian-arc series, built once per CRS in {@link #initialize()} and used only
	 * by the two polar aspects. Immutable and {@link java.io.Serializable}.
	 */
	private MeridianArc meridian;

	/**
	 * {@code Q->M1}, the meridian arc to {@code +lat_0}. Used by the {@code +guam} kernels and by
	 * nothing else, which is why it sat here unwritten until {@code +guam} was dispatched.
	 */
	private double M1;

	/** {@code +guam}. Only consulted when the figure is an ellipsoid; see {@link #setGuam}. */
	private boolean guam;
	private double N1;
	private double Mp;
	private double He;
	private double G;
	private double sinphi0, cosphi0;
	private Geodesic geodesic;
	
	/**
	 * A bare {@code +proj=aeqd}, i.e. the equatorial aspect.
	 * <p>
	 * <b>Was 90&deg;.</b> {@code Proj4Parser} assigns {@code +lat_0} only when the keyword is
	 * present, so this constructor is the effective default for every definition that omits it,
	 * and PROJ's default is 0. See {@link GnomonicAzimuthalProjection#GnomonicAzimuthalProjection()}
	 * for the same defect in the same family.
	 */
	public EquidistantAzimuthalProjection() {
		this(0.0, 0.0);
	}

	public EquidistantAzimuthalProjection(double projectionLatitude, double projectionLongitude) {
		super(projectionLatitude, projectionLongitude);
		initialize();
	}
	
	/**
	 * Retained for source compatibility. The coefficient array that used to need deep-copying here
	 * is gone; {@link MeridianArc} is immutable, so sharing the reference with the clone is correct.
	 */
	public Object clone() {
		return super.clone();
	}


	/**
	 * {@code +guam}: the Guam variant of the <em>ellipsoidal</em> forward and inverse
	 * ({@code aeqd.cpp:69-88} and {@code :189-204}).
	 *
	 * <p>Upstream reads it with {@code pj_param}'s {@code b} sigil, so {@code +guam=f} is
	 * explicitly off, and — crucially — it reads it <b>inside the {@code es != 0} arm</b> of
	 * {@code PJ_PROJECTION(aeqd)}. On a declared sphere the key is therefore consumed and
	 * ignored, not an error and not a different projection. {@link #initialize()} and
	 * {@link #project} reproduce that placement by testing {@code !spherical && guam}.
	 *
	 * <p>The variant is aspect-independent: it replaces the whole forward and inverse rather
	 * than one of the four {@code mode} branches, which is why {@link #initialize()} computes
	 * {@link #M1} instead of {@code Mp}/{@code N1}/{@code G}/{@code He}.
	 *
	 * @param guam whether to use the Guam formulation
	 * @since 1.5.0
	 */
	public void setGuam(boolean guam) {
		this.guam = guam;
	}

	/**
	 * @return whether {@code +guam} is in force
	 * @since 1.5.0
	 */
	public boolean isGuam() {
		return guam;
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
			meridian = MeridianArc.fromEs(es);
			if (guam) {
				// aeqd.cpp:301-304. The Guam arm needs only M1, and replaces both kernels, so
				// none of Mp / N1 / G / He is computed and no Geodesic is built. Derived purely
				// from projectionLatitude, hence idempotent across the two initialize() calls.
				M1 = meridian.mlfn(projectionLatitude, sinphi0, cosphi0);
				return;
			}
			switch (mode) {
			case NORTH_POLE:
				Mp = meridian.mlfn(ProjectionMath.HALFPI, 1., 0.);
				break;
			case SOUTH_POLE:
				Mp = meridian.mlfn(-ProjectionMath.HALFPI, -1., 0.);
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
		} else {
			// aeqd.cpp:276, geod_init(&Q->g, 1, P->f), is called UNCONDITIONALLY, before the
			// aspect dispatch and before the `P->es == 0` fork -- so a declared sphere has a
			// geodesic too, and aeqd_s_forward's degenerate branch relies on it: see
			// projectGeodesic. Flattening zero, radius `a` rather than the ellipsoid's, because
			// `+R=` replaces the figure and P->a is what upstream reads.
			geodesic = new Geodesic(a, 0.0);
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
				if (mode == EQUATOR)
					xy.y = cosphi * coslam;
				else
					xy.y = sinphi0 * sinphi + cosphi0 * cosphi * coslam;
				if (Math.abs(Math.abs(xy.y) - 1.) < TOL)
					if (xy.y < 0.)
						throw new ProjectionException(
								"aeqd: the point is antipodal to the centre of projection "
										+ "(cos(distance) = " + xy.y + "), where the azimuth "
										+ "is undefined (aeqd.cpp:152)");
					else
						// aeqd.cpp:155 and :182 -- NOT `xy.x = xy.y = 0.`. Within TOL of the
						// centre the spherical formula loses the azimuth to cancellation, so
						// upstream hands the point to the GEODESIC forward, which on a sphere
						// is an exact great-circle solution and keeps both ordinates. Returning
						// the origin instead was a 0.147 m error at the corpus's sample point.
						return projectGeodesic(lam, phi, xy);
				else {
					xy.y = Math.acos(xy.y);
					xy.y /= Math.sin(xy.y);
					xy.x = xy.y * cosphi * Math.sin(lam);
					xy.y *= (mode == EQUATOR) ? sinphi :
				   		cosphi0 * sinphi - sinphi0 * cosphi * coslam;
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
		} else if (guam) {
			// e_guam_fwd, aeqd.cpp:69-88. EPSG method 9831, "Guam Projection": a
			// second-order approximation that is aspect-independent, so it does not consult
			// `mode` at all.
			final double cosphi = Math.cos(phi);
			final double sinphi = Math.sin(phi);
			final double t = 1. / Math.sqrt(1. - es * sinphi * sinphi);
			xy.x = lam * cosphi * t;
			xy.y = meridian.mlfn(phi, sinphi, cosphi) - M1
					+ .5 * lam * lam * cosphi * sinphi * t;
		} else {
			double  coslam, cosphi, sinphi, rho;

			coslam = Math.cos(lam);
			cosphi = Math.cos(phi);
			sinphi = Math.sin(phi);
			switch (mode) {
			case NORTH_POLE:
				coslam = - coslam;
			case SOUTH_POLE:
				xy.x = (rho = Math.abs(Mp - meridian.mlfn(phi, sinphi, cosphi))) *
					Math.sin(lam);
				xy.y = rho * coslam;
				break;
			case EQUATOR:
			case OBLIQUE:
				return projectGeodesic(lam, phi, xy);
			}
		}
		return xy;
	}

	/**
	 * The {@code EQUIT}/{@code OBLIQ} arm of {@code aeqd_e_forward} ({@code aeqd.cpp:84-122}):
	 * one geodesic inverse solution from the centre of projection, giving a distance and an
	 * initial azimuth which are then read as polar coordinates.
	 * <p>
	 * Extracted because {@code aeqd_s_forward} calls it too. On a sphere ({@code f == 0}) the
	 * geodesic solver returns the exact great-circle solution, which is what makes it a valid
	 * substitute for the closed form near the centre rather than a change of projection.
	 *
	 * @param lam longitude relative to {@code +lon_0}, in radians
	 * @param phi latitude, in radians
	 * @param xy the destination
	 * @return {@code xy}
	 */
	private ProjCoordinate projectGeodesic(double lam, double phi, ProjCoordinate xy) {
		if (Math.abs(lam) < EPS10 && Math.abs(phi - projectionLatitude) < EPS10) {
			xy.x = xy.y = 0.;
			return xy;
		}
		GeodesicData g = geodesic.Inverse(
						ProjectionMath.toDeg(projectionLatitude),
						ProjectionMath.toDeg(projectionLongitude),
						ProjectionMath.toDeg(phi),
						ProjectionMath.toDeg(lam + projectionLongitude));
		double azi1 = ProjectionMath.toRad(g.azi1);
		xy.x = g.s12 * Math.sin(azi1) / geodesic.EquatorialRadius();
		xy.y = g.s12 * Math.cos(azi1) / geodesic.EquatorialRadius();
		return xy;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		if (spherical) {
			double cosc, c_rh, sinc;

			if ((c_rh = ProjectionMath.distance(x, y)) > Math.PI) {
				if (c_rh - EPS10 > Math.PI)
					throw new ProjectionException(); 
				c_rh = Math.PI;
			} else if (c_rh < EPS10) {
				lp.y = projectionLatitude;
				lp.x = 0.;
				return lp;
			}
			if (mode == OBLIQUE || mode == EQUATOR) {
				sinc = Math.sin(c_rh);
				cosc = Math.cos(c_rh);
				if (mode == EQUATOR) {
					lp.y = ProjectionMath.asin(y * sinc / c_rh);
					x *= sinc;
					y = cosc * c_rh;
				} else {
					lp.y = ProjectionMath.asin(cosc * sinphi0 + y * sinc * cosphi0 /
						c_rh);
					y = (cosc - sinphi0 * Math.sin(lp.y)) * c_rh;
					x *= sinc * cosphi0;
				}
				lp.x = y == 0. ? 0. : Math.atan2(x, y);
			} else if (mode == NORTH_POLE) {
				lp.y = ProjectionMath.HALFPI - c_rh;
				lp.x = Math.atan2(x, -y);
			} else {
				lp.y = c_rh - ProjectionMath.HALFPI;
				lp.x = Math.atan2(x, y);
			}
		} else if (guam) {
			// e_guam_inv, aeqd.cpp:189-204. Three fixed-point iterations, no convergence test
			// and no early return for the origin - upstream has neither, and at (0, 0) the
			// iteration lands on phi0 exactly anyway.
			//
			// `t` is deliberately carried OUT of the loop: the longitude uses the value from
			// the LAST iteration, i.e. evaluated at the previous latitude, not at the final
			// one. That is upstream's, and reproducing it matters - recomputing t from the
			// converged latitude changes the longitude in the 9th decimal.
			final double x2 = 0.5 * x * x;
			double t = 0.0;
			lp.y = projectionLatitude;
			for (int i = 0; i < 3; ++i) {
				t = e * Math.sin(lp.y);
				t = Math.sqrt(1. - t * t);
				lp.y = meridian.invMlfn(M1 + y - x2 * Math.tan(lp.y) * t);
			}
			lp.x = x * t / Math.cos(lp.y);
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
								ProjectionMath.toDeg(projectionLatitude),
								ProjectionMath.toDeg(projectionLongitude),
								ProjectionMath.toDeg(azi1),
								s12);
				lp.y = ProjectionMath.toRad(g.lat2);
				lp.x = ProjectionMath.toRad(g.lon2);
				lp.x -= projectionLongitude;
			} else {
				lp.y = meridian.invMlfn(mode == NORTH_POLE ? Mp - c : Mp + c);
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


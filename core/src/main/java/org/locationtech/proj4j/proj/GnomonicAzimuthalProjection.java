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
import org.locationtech.proj4j.geodesic.Geodesic;
import org.locationtech.proj4j.geodesic.GeodesicData;
import org.locationtech.proj4j.geodesic.GeodesicLine;
import org.locationtech.proj4j.geodesic.GeodesicMask;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Gnomonic, {@code +proj=gnom}.
 *
 * <p>Upstream is {@code 9.8.1:src/projections/gnom.cpp}, which has <b>two entirely different
 * kernels</b> and dispatches on {@code P-&gt;es == 0}:
 *
 * <ul>
 * <li><b>spherical</b> &mdash; the four-aspect closed form below, unchanged here;</li>
 * <li><b>ellipsoidal</b> &mdash; Karney's geodesic gnomonic. The forward is one geodesic inverse
 *     solution from {@code (lat_0, 0)}, giving the azimuth {@code azi1}, the reduced length
 *     {@code m12} and the geodesic scale {@code M12}; {@code rho = m12 / M12} and the point is
 *     {@code (rho sin azi, rho cos azi)}. The inverse is a 10-step Newton iteration along a
 *     {@link GeodesicLine} from the projection centre.</li>
 * </ul>
 *
 * <p><b>This class had only the spherical kernel</b>, so every ellipsoidal {@code gnom} silently
 * answered the spherical formulas: the corpus's {@code +proj=gnom +a=1 +rf=200} blocks missed by
 * 7 mm to 28.7 m against a 0.1 mm bar, and — more seriously — <em>the domain contract differs
 * between the two</em>. On a sphere the polar aspect cannot see the equator at all, so
 * {@code +lat_0=90} at {@code (0, 0)} is an error; on the ellipsoid it is a finite point,
 * {@code (0, -127.4835)}. Three corpus rows assert the finite answer and three assert the error,
 * and only a class with both kernels can satisfy both sets.
 *
 * <p>The geodesic runs on a <b>unit-{@code a}</b> ellipsoid ({@code geod_init(&amp;Q-&gt;g, 1,
 * P-&gt;f)}), so {@code rho} is dimensionless and the {@code a} multiply happens in
 * {@code Projection}'s affine tail exactly as it does for the spherical kernel. Getting that wrong
 * scales the whole map by {@code a}.
 *
 * @see <a href="https://github.com/OSGeo/PROJ/blob/9.8.1/src/projections/gnom.cpp">9.8.1
 *      gnom.cpp</a>
 */
public class GnomonicAzimuthalProjection extends AzimuthalProjection {

	private static final long serialVersionUID = -2763703781166745739L;

	/** Upstream's {@code numit_}. */
	private static final int MAX_ITER = 10;

	/** Upstream's {@code eps_} = {@code 0.01 * sqrt(DBL_EPSILON)}. */
	private static final double EPS_NEWTON = 0.01 * Math.sqrt(Math.ulp(1.0));

	/**
	 * {@code Q-&gt;g}, built on a unit-{@code a} ellipsoid. Null when the figure is a sphere, which
	 * is also how the two kernels are selected — mirroring upstream's {@code P-&gt;es == 0} test.
	 */
	private Geodesic geodesic;
	
	/**
	 * A bare {@code +proj=gnom}, i.e. the equatorial aspect.
	 * <p>
	 * <b>The no-argument constructor used to pin {@code lat_0} to 90&deg;</b>, which selected
	 * {@link AzimuthalProjection#NORTH_POLE} for every definition that did not spell
	 * {@code +lat_0} out. {@code Proj4Parser} assigns {@code +lat_0} <em>only when the keyword is
	 * present</em>, so the class default is the effective default, and PROJ's is 0
	 * ({@code pj_init} reads {@code "rlat_0"}, whose absence yields 0). {@code +proj=gnom +R=1}
	 * therefore answered the polar formulas where PROJ answers the equatorial ones: at
	 * {@code (10, 80)} it returned {@code (0.030619, -0.173648)} against PROJ's
	 * {@code (0.1763, 5.7588)} &mdash; a different projection, not a rounding difference.
	 */
	public GnomonicAzimuthalProjection() {
		this(0.0, 0.0);
	}

	public GnomonicAzimuthalProjection(double projectionLatitude, double projectionLongitude) {
		super(projectionLatitude, projectionLongitude);
		minLatitude = ProjectionMath.toRad(0);
		maxLatitude = ProjectionMath.toRad(90);
		initialize();
	}
	
	/**
	 * Selects the kernel. Port of {@code PJ_PROJECTION(gnom)}
	 * ({@code 9.8.1:src/projections/gnom.cpp:181-213}).
	 */
	public void initialize() {
		super.initialize();
		if (es == 0.0) {
			geodesic = null;
		} else {
			// geod_init(&Q->g, 1, P->f): unit equatorial radius, real flattening.
			geodesic = new Geodesic(1.0, 1.0 - Math.sqrt(one_es));
		}
	}

	public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
		if (geodesic != null) {
			return projectEllipsoidal(lam, phi, xy);
		}
		double sinphi = Math.sin(phi);
		double cosphi = Math.cos(phi);
		double coslam = Math.cos(lam);

		switch (mode) {
		case EQUATOR:
			xy.y = cosphi * coslam;
			break;
		case OBLIQUE:
			xy.y = sinphi0 * sinphi + cosphi0 * cosphi * coslam;
			break;
		case SOUTH_POLE:
			xy.y = -sinphi;
			break;
		case NORTH_POLE:
			xy.y = sinphi;
			break;
		}
		// gnom.cpp:50 -- the test is `xy.y <= EPS10`, NOT `fabs(xy.y) <= EPS10`. xy.y is
		// cos(angular distance from the centre), so a NEGATIVE value is a point beyond the
		// horizon and must be refused; taking the absolute value accepted the whole far
		// hemisphere and projected it, mirrored, onto the near one.
		if (xy.y <= EPS10)
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"Gnomonic: cos(distance from the projection centre) = " + xy.y
							+ " is at or beyond the horizon, where the gnomonic plane is not "
							+ "defined");
		xy.x = (xy.y = 1. / xy.y) * cosphi * Math.sin(lam);
		switch (mode) {
		case EQUATOR:
			xy.y *= sinphi;
			break;
		case OBLIQUE:
			xy.y *= cosphi0 * sinphi - sinphi0 * cosphi * coslam;
			break;
		case NORTH_POLE:
			coslam = -coslam;
		case SOUTH_POLE:
			xy.y *= cosphi * coslam;
			break;
		}
		return xy;
	}

	/**
	 * Port of {@code gnom_e_forward} ({@code 9.8.1:src/projections/gnom.cpp:115-136}).
	 *
	 * @param lam longitude relative to the central meridian, radians
	 * @param phi latitude, radians
	 * @param xy the output
	 * @return {@code xy}
	 * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} when the geodesic
	 *         scale {@code M12} is not positive, i.e. the point is at or beyond the point where
	 *         geodesics from the centre stop diverging. Upstream sets
	 *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN} and returns
	 *         {@code HUGE_VAL}.
	 */
	private ProjCoordinate projectEllipsoidal(double lam, double phi, ProjCoordinate xy) {
		GeodesicData g = geodesic.Inverse(
				ProjectionMath.toDeg(projectionLatitude), 0.0, ProjectionMath.toDeg(phi), ProjectionMath.toDeg(lam),
				GeodesicMask.AZIMUTH | GeodesicMask.REDUCEDLENGTH | GeodesicMask.GEODESICSCALE);
		if (!(g.M12 > 0)) {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"Gnomonic (ellipsoidal): geodesic scale M12 = " + g.M12 + " at ("
							+ Math.toDegrees(lam) + ", " + Math.toDegrees(phi)
							+ ") degrees is not positive, so the gnomonic projection of the point "
							+ "is not defined");
		}
		double rho = g.m12 / g.M12;
		double azi = ProjectionMath.toRad(g.azi1);
		xy.x = rho * FastStrictTrig.sin(azi);
		xy.y = rho * FastStrictTrig.cos(azi);
		return xy;
	}

	/**
	 * Port of {@code gnom_e_inverse} ({@code 9.8.1:src/projections/gnom.cpp:138-178}).
	 *
	 * <p>Two details are load bearing and are copied literally. The iteration solves
	 * {@code rho(s) = rho} for {@code rho <= 1} and {@code 1/rho(s) = 1/rho} above it, because the
	 * two have well-conditioned derivatives on opposite sides of that line; and the convergence
	 * test is written <em>inverted</em>, {@code !(|ds| >= eps)}, so that a {@code NaN} takes the
	 * converged branch rather than spinning out the trip count.
	 *
	 * @param x easting, in units of the equatorial radius
	 * @param y northing, in units of the equatorial radius
	 * @param lp the output
	 * @return {@code lp}
	 * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} if the Newton iteration
	 *         does not converge in {@link #MAX_ITER} steps, which upstream reports as
	 *         {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}
	 */
	private ProjCoordinate projectInverseEllipsoidal(double x, double y, ProjCoordinate lp) {
		double azi0 = ProjectionMath.toDeg(Math.atan2(x, y)); // clockwise from north
		double rho = ProjectionMath.distance(x, y);
		double s = Math.atan(rho);
		boolean little = rho <= 1;
		if (!little) {
			rho = 1 / rho;
		}
		GeodesicLine line = geodesic.Line(
				ProjectionMath.toDeg(projectionLatitude), 0.0, azi0,
				GeodesicMask.LATITUDE | GeodesicMask.LONGITUDE | GeodesicMask.DISTANCE_IN
						| GeodesicMask.REDUCEDLENGTH | GeodesicMask.GEODESICSCALE);
		int count = MAX_ITER;
		boolean trip = false;
		double lat1 = 0;
		double lon1 = 0;
		while (count-- > 0) {
			GeodesicData g = line.Position(s,
					GeodesicMask.LATITUDE | GeodesicMask.LONGITUDE
							| GeodesicMask.REDUCEDLENGTH | GeodesicMask.GEODESICSCALE);
			lat1 = g.lat2;
			lon1 = g.lon2;
			if (trip) {
				break;
			}
			double ds = little ? (g.m12 - rho * g.M12) * g.M12 : (rho * g.m12 - g.M12) * g.m12;
			s -= ds;
			// Reversed test to allow escape with NaNs, exactly as upstream writes it.
			if (!(Math.abs(ds) >= EPS_NEWTON)) {
				trip = true;
			}
		}
		if (!trip) {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"Gnomonic (ellipsoidal) inverse: the Newton iteration did not reach "
							+ EPS_NEWTON + " in " + MAX_ITER + " steps for (" + x + ", " + y + ")");
		}
		lp.y = ProjectionMath.toRad(lat1);
		lp.x = ProjectionMath.toRad(lon1);
		return lp;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		if (geodesic != null) {
			return projectInverseEllipsoidal(x, y, lp);
		}
		double  rh, cosz, sinz;

		rh = ProjectionMath.distance(x, y);
		sinz = Math.sin(lp.y = Math.atan(rh));
		cosz = Math.sqrt(1. - sinz * sinz);
		if (Math.abs(rh) <= EPS10) {
			lp.y = projectionLatitude;
			lp.x = 0.;
		} else {
			switch (mode) {
			case OBLIQUE:
				lp.y = cosz * sinphi0 + y * sinz * cosphi0 / rh;
				if (Math.abs(lp.y) >= 1.)
					lp.y = lp.y > 0. ? ProjectionMath.HALFPI : - ProjectionMath.HALFPI;
				else
					lp.y = Math.asin(lp.y);
				y = (cosz - sinphi0 * Math.sin(lp.y)) * rh;
				x *= sinz * cosphi0;
				break;
			case EQUATOR:
				lp.y = y * sinz / rh;
				if (Math.abs(lp.y) >= 1.)
					lp.y = lp.y > 0. ? ProjectionMath.HALFPI : - ProjectionMath.HALFPI;
				else
					lp.y = Math.asin(lp.y);
				y = cosz * rh;
				x *= sinz;
				break;
			case SOUTH_POLE:
				lp.y -= ProjectionMath.HALFPI;
				break;
			case NORTH_POLE:
				lp.y = ProjectionMath.HALFPI - lp.y;
				y = -y;
				break;
			}
			lp.x = Math.atan2(x, y);
		}
		return lp;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Gnomonic Azimuthal";
	}

}

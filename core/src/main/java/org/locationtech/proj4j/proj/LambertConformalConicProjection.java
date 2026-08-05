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

package org.locationtech.proj4j.proj;

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.datum.Ellipsoid;
import org.locationtech.proj4j.util.ConformalLat;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Lambert Conformal Conic, {@code 9.8.1:src/projections/lcc.cpp}.
 *
 * <p>Every latitude-dependent quantity here is the conformal one, so the whole projection rests on
 * {@code pj_tsfn} and {@code pj_phi2}. Both now come from {@link ConformalLat}, the port of
 * 9.8.1's rewritten {@code src/tsfn.cpp} and {@code src/phi2.cpp}, rather than from the deprecated
 * {@code ProjectionMath} equivalents:
 *
 * <ul>
 * <li>the forward and the three {@code initialize()} sites use {@code tsfn}, whose 9.8.1 form
 *     ({@code exp(e*atanh(e*sin phi))} times a cancellation-free ratio) drops a {@code tan} and a
 *     {@code pow} and is exactly {@code 1.0} at the equator where the old one was one ulp short;
 * <li>the inverse uses {@code phi2}, which is Newton on {@code tau} and converges in one or two
 *     trips. On GRS80 the old 15-step {@code pow}-per-trip loop was up to 4,145 nm from the truth;
 *     measured on {@code builtins.gie:3767-3768} ({@code +lat_1=0.5 +lat_2=2}, inverse of
 *     {@code (200, 100)}) the latitude was <b>23.1 um</b> out against that row's 0.1 mm bar, and is
 *     now under 1 nm.
 * </ul>
 *
 * <p>Because {@code n} is derived from {@code log(ml1/ml2)} of two {@code tsfn} values, the cone
 * constant itself moves by about one ulp, and with it every {@code lcc} coordinate. That is
 * expected and is the point.
 *
 * <p><b>Setup validation.</b> {@code initialize()} now reproduces {@code lcc.cpp:100-110} and
 * {@code :122-138}/{@code :154-161}: a standard parallel at or beyond the pole, and a cone constant
 * that comes out exactly zero, are rejected instead of being carried forward as a division by zero.
 * {@code builtins.gie:3862-3908} asserts eight such setups as {@code expect failure errno
 * invalid_op_illegal_arg_value}, and proj4j accepted all eight, producing coordinates from an
 * infinite or NaN cone constant. This <b>throws where it used to return</b>; no definition in the
 * bundled EPSG, ESRI or NAD tables — 1,885 {@code +proj=lcc} definitions — has {@code |lat_1| >= 90}
 * or {@code |lat_2| >= 90}, and those with no {@code lat_1} at all already threw on the
 * {@code |lat_1 + lat_2| > 0} check that was already here.
 */
public class LambertConformalConicProjection extends ConicProjection {

	private static final long serialVersionUID = 565492101400462955L;

	private double n;
	private double rho0;
	private double c;

	public LambertConformalConicProjection() {
		minLatitude = ProjectionMath.toRad(0);
		maxLatitude = ProjectionMath.toRad(80.0);
		// an incorrect init, LCC is sensitive to input parameters
		// init should happen only after the LCC projection parsing
		// projectionLatitude = ProjectionMath.QUARTERPI;
		projectionLatitude1 = 0;
		projectionLatitude2 = 0;
		// initialize();
	}

	/**
	* Set up a projection suitable for State Place Coordinates.
	*/
	public LambertConformalConicProjection(Ellipsoid ellipsoid, double lon_0, double lat_1, double lat_2, double lat_0, double x_0, double y_0) {
		setEllipsoid(ellipsoid);
		projectionLongitude = lon_0;
		projectionLatitude = lat_0;
		scaleFactor = 1.0;
		falseEasting = x_0;
		falseNorthing = y_0;
		projectionLatitude1 = lat_1;
		projectionLatitude2 = lat_2;
		initialize();
	}

	public ProjCoordinate project(double x, double y, ProjCoordinate out) {
		double rho;
		if (Math.abs(Math.abs(y) - ProjectionMath.HALFPI) < 1e-10)
			rho = 0.0;
		else {
			rho = c * (spherical ?
			    Math.pow(Math.tan(ProjectionMath.QUARTERPI + .5 * y), -n) :
			      Math.pow(ConformalLat.tsfn(y, Math.sin(y), e), n));
    }
		out.x = scaleFactor * (rho * Math.sin(x *= n));
		out.y = scaleFactor * (rho0 - rho * Math.cos(x));
		return out;
	}

	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate out) {
		// https://github.com/OSGeo/PROJ/blob/9.6/src/projections/lcc.cpp#L49-L53
		x /= scaleFactor;
		y /= scaleFactor;
		y = rho0 - y;
		double rho = ProjectionMath.distance(x, y);
		if (rho != 0) {
			if (n < 0.0) {
				rho = -rho;
				x = -x;
				y = -y;
			}
			if (spherical)
				out.y = 2.0 * Math.atan(Math.pow(c / rho, 1.0 / n)) - ProjectionMath.HALFPI;
			else
				out.y = ConformalLat.phi2(Math.pow(rho / c, 1.0 / n), e);
			out.x = Math.atan2(x, y) / n;
		} else {
			out.x = 0.0;
			out.y = n > 0.0 ? ProjectionMath.HALFPI : -ProjectionMath.HALFPI;
		}
		return out;
	}

	public void initialize() {
		super.initialize();
		double cosphi, sinphi;
		boolean secant;

		// Old code:
		// if ( projectionLatitude1 == 0 )
			// projectionLatitude1 = projectionLatitude2 = projectionLatitude;

		// https://github.com/OSGeo/PROJ/blob/e3d7e18f988230973ced5163fa2581b6671c8755/src/projections/lcc.cpp#L89-L96
		// if there is no lat2 set it to lat1
		if (projectionLatitude2 == 0) {
			projectionLatitude2 = projectionLatitude1;
			// if there is no lat0, set it to lat1
			if(projectionLatitude == 0)
				projectionLatitude = projectionLatitude1;
		}


		// Left as ProjectionException deliberately. 149 of the 1,885 bundled +proj=lcc definitions
		// omit lat_1 entirely and so already reach this throw; reclassifying it to
		// InvalidValueException -- which is the taxonomically correct answer, since this is a
		// setup error carrying PROJ's invalid_op_illegal_arg_value -- would change the exception
		// type every one of those callers sees, and the two classes are siblings rather than
		// related by inheritance. That reclassification belongs with the error-taxonomy work, not
		// here. None of the eight rejection rows at builtins.gie:3862-3908 reaches this line.
		if (Math.abs(projectionLatitude1 + projectionLatitude2) < 1e-10)
			throw new ProjectionException(
				"Invalid value for lat_1 and lat_2: |lat_1 + lat_2| should be > 0");
		n = sinphi = Math.sin(projectionLatitude1);
		cosphi = Math.cos(projectionLatitude1);
		// lcc.cpp:100-110. Without these the standard parallels may sit at or beyond the pole,
		// where the cone degenerates and every subsequent quantity is a division by zero dressed
		// up as a coordinate. builtins.gie:3876-3908 asserts eight such setups as rejections.
		if (Math.abs(cosphi) < 1e-10 || Math.abs(projectionLatitude1) >= ProjectionMath.HALFPI)
			throw new InvalidValueException("Invalid value for lat_1: |lat_1| should be < 90 degrees");
		if (Math.abs(Math.cos(projectionLatitude2)) < 1e-10
				|| Math.abs(projectionLatitude2) >= ProjectionMath.HALFPI)
			throw new InvalidValueException("Invalid value for lat_2: |lat_2| should be < 90 degrees");
		secant = Math.abs(projectionLatitude1 - projectionLatitude2) >= 1e-10;
		spherical = (es == 0.0);
		if (!spherical) {
			double ml1, m1;

			m1 = ProjectionMath.msfn(sinphi, cosphi, es);
			ml1 = ConformalLat.tsfn(projectionLatitude1, sinphi, e);
			if (secant) {
				n = Math.log(m1 /
				   ProjectionMath.msfn(sinphi = Math.sin(projectionLatitude2), Math.cos(projectionLatitude2), es));
				// lcc.cpp:122-128 and :132-138: es so close to 1 that the two msfn values, or the
				// two tsfn values, are indistinguishable. Dividing by the resulting zero used to
				// yield an infinite cone constant. builtins.gie:3862 and :3869 are these two.
				if (n == 0.0)
					throw new InvalidValueException("Invalid value for eccentricity");
				double denom = Math.log(ml1 / ConformalLat.tsfn(projectionLatitude2, sinphi, e));
				if (denom == 0.0)
					throw new InvalidValueException("Invalid value for eccentricity");
				n /= denom;
			}
			c = (rho0 = m1 * Math.pow(ml1, -n) / n);
			rho0 *= (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) < 1e-10) ? 0. :
				Math.pow(ConformalLat.tsfn(projectionLatitude, Math.sin(projectionLatitude), e), n);
		} else {
			if (secant)
				n = Math.log(cosphi / Math.cos(projectionLatitude2)) /
				   Math.log(Math.tan(ProjectionMath.QUARTERPI + .5 * projectionLatitude2) /
				   Math.tan(ProjectionMath.QUARTERPI + .5 * projectionLatitude1));
			// lcc.cpp:154-161: reachable with +proj=lcc +a=1 +lat_2=.0000001, upstream's own
			// example, where lat_1 and lat_2 are too close to zero to distinguish.
			if (n == 0.0)
				throw new InvalidValueException(
					"Invalid value for lat_1 and lat_2: |lat_1 + lat_2| should be > 0");
			c = cosphi * Math.pow(Math.tan(ProjectionMath.QUARTERPI + .5 * projectionLatitude1), n) / n;
			rho0 = (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) < 1e-10) ? 0. :
				c * Math.pow(Math.tan(ProjectionMath.QUARTERPI + .5 * projectionLatitude), -n);
		}
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

	public String toString() {
		return "Lambert Conformal Conic";
	}

}


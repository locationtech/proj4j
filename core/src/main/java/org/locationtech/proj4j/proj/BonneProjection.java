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
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Bonne (Werner when {@code lat_1 = 90}), {@code 9.8.1:src/projections/bonne.cpp}.
 *
 * <p>The ellipsoidal branch is meridian-arc arithmetic throughout, so
 * {@code ProjectionMath.enfn}/{@code mlfn}/{@code inv_mlfn} are replaced by {@link MeridianArc},
 * the port of {@code 9.8.1:src/mlfn.cpp}. Two consequences beyond accuracy: the series is 6th
 * order in the <b>third flattening</b> rather than in {@code es}, and the inverse is
 * <b>closed form</b> where proj4j ran a ten-step Newton loop against its own forward.
 *
 * <p>Three defects were in the way of asserting anything about this projection against
 * {@code builtins.gie:671-780}, and all three are fixed here because they are all in this file.
 *
 * <ol>
 * <li><b>{@code +lat_1} was ignored.</b> {@code phi1} was hard-wired to {@code pi/2} with the
 *     parameter read commented out, so {@code +proj=bonne +lat_1=0.5} silently produced the
 *     Werner aspect: forward of {@code (2, 1)} landed 9,944 km from the value
 *     {@code builtins.gie:674} expects. {@code bonne.cpp:126-131} reads {@code lat_1} and errors
 *     when {@code |lat_1| < 1e-10}; that is now what happens.
 * <li><b>The ellipsoidal forward had no small-{@code rh} guard.</b> {@code bonne.cpp:29-35} wraps
 *     the whole body in {@code if (fabs(rh) > EPS10)} and yields {@code (0, 0)} otherwise. Without
 *     it, {@code +lat_1=90} at the pole computes {@code 0/0}: {@code am1} there is
 *     {@code 6.1e-17} while {@code m1} is {@code 1.57}, so {@code am1 + m1} rounds back to
 *     {@code m1} and {@code rh} is exactly zero. {@code builtins.gie:706-712} expects
 *     {@code (0, 0)}; proj4j returned {@code (NaN, NaN)}.
 * <li><b>The inverse read the northing it had just overwritten.</b> {@code rh} was computed from
 *     {@code am1 - y} — correctly — but the {@code atan2} that recovers the longitude was still
 *     passed the <em>original</em> {@code y}. For {@code +lat_1=0.5}, {@code am1} is about 114.6
 *     and {@code y/a} about {@code 1.6e-5}, so {@code atan2} was handed operands differing by
 *     seven orders of magnitude and returned 1.1 rad instead of 2.7e-7. Upstream mutates
 *     {@code xy.y} in place ({@code bonne.cpp:80-98}) precisely so that both uses see the shifted
 *     value; the shifted value is now a named local. The same passage supplies the
 *     {@code copysign(hypot(...), phi1)} and the negated {@code atan2} arguments for southern
 *     {@code lat_1}, which proj4j also lacked.
 * </ol>
 */
public class BonneProjection extends Projection {

	private static final long serialVersionUID = 5102707395837530041L;

	private double phi1;
	private double cphi1;
	private double am1;
	private double m1;

	/**
	 * The 6th-order meridian-arc series. Built once per CRS in {@link #initialize()} — its
	 * coefficients depend only on the ellipsoid — and immutable and {@link java.io.Serializable}
	 * thereafter.
	 */
	private MeridianArc meridian;

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		if (spherical) {
			double E, rh;

			rh = cphi1 + phi1 - lpphi;
			if (Math.abs(rh) > EPS10) {
				out.x = rh * Math.sin(E = lplam * Math.cos(lpphi) / rh);
				out.y = cphi1 - rh * Math.cos(E);
			} else
				out.x = out.y = 0.;
		} else {
			double rh, E, c;

			rh = am1 + m1 - meridian.mlfn(lpphi, E = Math.sin(lpphi), c = Math.cos(lpphi));
			// bonne.cpp:30: the guard is on rh, and it is what keeps +lat_1=90 at the pole from
			// evaluating 0/0.
			if (Math.abs(rh) > EPS10) {
				E = c * lplam / (rh * Math.sqrt(1. - es * E * E));
				out.x = rh * Math.sin(E);
				out.y = am1 - rh * Math.cos(E);
			} else
				out.x = out.y = 0.;
		}
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		if (spherical) {
			// bonne.cpp:53-75. dy is the shifted northing; both the radius and the atan2 must
			// see it, which is why upstream assigns it back into xy.y.
			double dy = cphi1 - xyy;
			double rh = Math.copySign(ProjectionMath.distance(xyx, dy), phi1);
			out.y = cphi1 + phi1 - rh;
			double absPhi = Math.abs(out.y);
			if (absPhi > ProjectionMath.HALFPI) throw new ProjectionException("I");
			if (ProjectionMath.HALFPI - absPhi <= EPS10)
				out.x = 0.;
			else {
				double lm = rh / Math.cos(out.y);
				out.x = phi1 > 0 ? lm * Math.atan2(xyx, dy) : lm * Math.atan2(-xyx, -dy);
			}
		} else {
			// bonne.cpp:78-102.
			double dy = am1 - xyy;
			double rh = Math.copySign(ProjectionMath.distance(xyx, dy), phi1);
			out.y = meridian.invMlfn(am1 + m1 - rh);
			double absPhi = Math.abs(out.y);
			if (absPhi < ProjectionMath.HALFPI) {
				double s = Math.sin(out.y);
				double lm = rh * Math.sqrt(1. - es * s * s) / Math.cos(out.y);
				out.x = phi1 > 0 ? lm * Math.atan2(xyx, dy) : lm * Math.atan2(-xyx, -dy);
			} else if (absPhi - ProjectionMath.HALFPI <= EPS10)
				out.x = 0.;
			else throw new ProjectionException("I");
		}
		return out;
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

	public void initialize() {
		super.initialize();

		double c;

		// bonne.cpp:126-131: lat_1 is a required parameter, and |lat_1| == 0 is an error rather
		// than a silent fallback to the Werner aspect.
		phi1 = projectionLatitude1;
		if (Math.abs(phi1) < EPS10)
			throw new ProjectionException("Invalid value for lat_1: |lat_1| should be > 0");
		if (!spherical) {
			meridian = MeridianArc.fromEs(es);
			am1 = Math.sin(phi1);
			c = Math.cos(phi1);
			m1 = meridian.mlfn(phi1, am1, c);
			am1 = c / (Math.sqrt(1. - es * am1 * am1) * am1);
		} else {
			if (Math.abs(phi1) + EPS10 >= ProjectionMath.HALFPI)
				cphi1 = 0.;
			else
				cphi1 = 1. / Math.tan(phi1);
		}
	}

	public String toString() {
		return "Bonne";
	}

}

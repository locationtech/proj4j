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
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Airy ({@code +proj=airy}), ported from {@code 9.8.1:src/projections/airy.cpp}.
 *
 * <p>The polar branch of the forward was the whole defect, and it is the "lost in-place
 * reassignment" shape: upstream writes {@code lp.phi = fabs(p_halfpi - lp.phi)} and then reads
 * {@code lp.phi} <em>three</em> more times, while the old translation staged that value in the
 * output coordinate and read the original latitude for all three. See {@link #project} for the two
 * measured consequences.
 *
 * <p>{@code +no_cut} and {@code +lat_b} are still not {@code Proj4Keyword}s. {@link #setNoCut} and
 * {@link #setLatB} exist so that registering them is a parser-only change; until then the bridge
 * reports both correctly as not implemented rather than silently ignoring them.
 */
public class AiryProjection extends Projection {

	private static final long serialVersionUID = 1869315092720877608L;

	private double p_halfpi;
	private double sinph0;
	private double cosph0;
	private double Cb;
	private int mode;

	/**
	 * {@code +no_cut}: do NOT cut at the hemisphere limit.
	 * <p>
	 * The default is {@code false} — {@code pj_param(..., "bno_cut").i} is 0 for an absent key, so
	 * upstream cuts. The field used to initialise to {@code true} and {@code initialize()} then
	 * overwrote it with {@code false} behind a {@code //FIXME}, which meant a caller could not set
	 * it at all and the state was not idempotent across the two {@code initialize()} calls.
	 */
	private boolean no_cut;

	/** {@code +lat_b}, radians. {@code pj_param(..., "rlat_b")} is 0 for an absent key. */
	private double lat_b;

	private final static double EPS = 1.e-10;
	private final static int N_POLE = 0;
	private final static int S_POLE = 1;
	private final static int EQUIT = 2;
	private final static int OBLIQ = 3;

	public AiryProjection() {
		minLatitude = ProjectionMath.toRad(-60);
		maxLatitude = ProjectionMath.toRad(60);
		minLongitude = ProjectionMath.toRad(-90);
		maxLongitude = ProjectionMath.toRad(90);
		initialize();
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double sinlam, coslam, cosphi, sinphi, t, s, Krho, cosz;

		sinlam = FastStrictTrig.sin(lplam);
		coslam = FastStrictTrig.cos(lplam);
		switch (mode) {
		case EQUIT:
		case OBLIQ:
			sinphi = FastStrictTrig.sin(lpphi);
			cosphi = FastStrictTrig.cos(lpphi);
			cosz = cosphi * coslam;
			if (mode == OBLIQ)
				cosz = sinph0 * sinphi + cosph0 * cosz;
			if (!no_cut && cosz < -EPS)
				throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
						"(" + Math.toDegrees(lplam) + ", " + Math.toDegrees(lpphi) + ") deg is on "
								+ "the far hemisphere (cos of the zenith angle is " + cosz
								+ "); use +no_cut to project it anyway");
			s = 1. - cosz;
			if (Math.abs(s) > EPS) {
				t = 0.5 * (1. + cosz);
				if (t == 0) {
					// airy.cpp:73-78: the exact antipode, where -log(t) is +Infinity.
					throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
							"(" + Math.toDegrees(lplam) + ", " + Math.toDegrees(lpphi)
									+ ") deg is the antipode of the projection centre");
				}
				Krho = -Math.log(t)/s - Cb / t;
			} else
				Krho = 0.5 - Cb;
			out.x = Krho * cosphi * sinlam;
			if (mode == OBLIQ)
				out.y = Krho * (cosph0 * sinphi -
					sinph0 * cosphi * coslam);
			else
				out.y = Krho * sinphi;
			break;
		case S_POLE:
		case N_POLE:
			// airy.cpp:85-102 REASSIGNS lp.phi and then reads the reassigned value three more
			// times: in the no_cut guard, in the halving, and in tan()/log(cos()). The old
			// translation staged the reassignment in the OUTPUT slot (out.y) and then read the
			// ORIGINAL lpphi for all three - so at lat_0=90 the forward of (0, 0) evaluated
			// log(cos(0))/tan(0) = 0/0 and returned a non-finite coordinate where PROJ returns
			// -1.3863, and the forward of (0, -90), which PROJ rejects as outside the domain,
			// evaluated tan(-pi/2) and returned a plausible-looking 1.132e16.
			double polarPhi = Math.abs(p_halfpi - lpphi);
			if (!no_cut && (polarPhi - EPS) > ProjectionMath.HALFPI)
				throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
						"(" + Math.toDegrees(lplam) + ", " + Math.toDegrees(lpphi) + ") deg is "
								+ Math.toDegrees(polarPhi) + " deg from the projection centre, "
								+ "past the hemisphere limit; use +no_cut to project it anyway");
			polarPhi *= 0.5;
			if (polarPhi > EPS) {
				t = FastStrictTrig.tan(polarPhi);
				Krho = -2.*(Math.log(FastStrictTrig.cos(polarPhi)) / t + t * Cb);
				out.x = Krho * sinlam;
				out.y = Krho * coslam;
				if (mode == N_POLE)
					out.y = -out.y;
			} else
				out.x = out.y = 0.;
			break;
		default:
			throw new ProjectionException(ErrorCause.NUMERICAL_FAILURE, this,
					"unreachable Airy mode " + mode);
		}
		return out;
	}

	/**
	 * {@code +no_cut}: project the far hemisphere instead of rejecting it.
	 * <p>
	 * <b>{@code no_cut} is not a {@code Proj4Keyword}, so nothing calls this yet.</b> The setter
	 * exists so that registering the key is a one-line parser change; without it the parser has
	 * nowhere to dispatch to and the key cannot be registered at all. {@code builtins.gie}'s one
	 * {@code +proj=airy +R=1 +lat_0=-90 +no_cut} block stays reported as not implemented until both
	 * halves land together.
	 *
	 * @param no_cut whether to skip the hemisphere-limit rejection
	 */
	public void setNoCut(boolean no_cut) {
		this.no_cut = no_cut;
	}

	public boolean isNoCut() {
		return no_cut;
	}

	/**
	 * {@code +lat_b}, in radians: the latitude at which the projection's Airy criterion is
	 * minimised. Defaults to 0, which is what {@code pj_param(..., "rlat_b")} answers for an absent
	 * key. Same dispatch gap as {@link #setNoCut(boolean)}.
	 *
	 * @param lat_b the balancing latitude, radians
	 */
	public void setLatB(double lat_b) {
		this.lat_b = lat_b;
	}

	/** @param lat_b the balancing latitude, degrees */
	public void setLatBDegrees(double lat_b) {
		this.lat_b = ProjectionMath.DTR * lat_b;
	}

	public double getLatB() {
		return lat_b;
	}

	/**
	 * {@code PJ_PROJECTION(airy)} ({@code airy.cpp:106-146}).
	 * <p>
	 * {@code es = 0} goes before {@code super.initialize()}, which derives {@code spherical},
	 * {@code one_es} and {@code rone_es} from it. Every value written here derives from
	 * {@link #projectionLatitude} and {@link #lat_b}, so the second call the parser makes is a
	 * no-op — which the {@code no_cut} assignment this method used to carry was not.
	 */
	public void initialize() { // airy
		es = 0.;
		e = 0.;
		super.initialize();

		double beta;

		beta = 0.5 * (ProjectionMath.HALFPI - lat_b);
		if (Math.abs(beta) < EPS)
			Cb = -0.5;
		else {
			Cb = 1./Math.tan(beta);
			Cb *= Cb * Math.log(Math.cos(beta));
		}
		if (Math.abs(Math.abs(projectionLatitude) - ProjectionMath.HALFPI) < EPS)
			if (projectionLatitude < 0.) {
				p_halfpi = -ProjectionMath.HALFPI;
				mode = S_POLE;
			} else {
				p_halfpi =  ProjectionMath.HALFPI;
				mode = N_POLE;
			}
		else {
			if (Math.abs(projectionLatitude) < EPS)
				mode = EQUIT;
			else {
				mode = OBLIQ;
				sinph0 = Math.sin(projectionLatitude);
				cosph0 = Math.cos(projectionLatitude);
			}
		}
	}

	public String toString() {
		return "Airy";
	}

}

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
import org.locationtech.proj4j.util.MeridianArc;
import org.locationtech.proj4j.util.ProjectionMath;

/**
 * Sinusoidal (Sanson-Flamsteed), {@code +proj=sinu}.
 *
 * <p>Upstream declares it {@code "PCyl, Sph&amp;Ell"} and dispatches on {@code P-&gt;es}
 * ({@code 9.8.1:src/projections/gn_sinu.cpp:246-256}). <b>This class had only the spherical
 * branch</b>, so {@code +proj=sinu +ellps=GRS80} ran {@code x = lam cos(phi)}, {@code y = phi}
 * against upstream's meridian arc — 745 m of northing error at the corpus's test point, and 8 of
 * its 16 {@code builtins.gie} assertions.
 *
 * <pre>
 * ellipsoidal:  y = M(phi)                        x = lam cos(phi) / sqrt(1 - es sin^2 phi)
 * inverse:      phi = M^-1(y)                     lam = x sqrt(1 - es sin^2 phi) / cos(phi)
 * </pre>
 *
 * <p>The spherical arm is unchanged and remains exact: for {@code sinu} upstream's
 * {@code C_x = C_y = 1}, {@code m = 0} and {@code n = 1}, which collapses the general
 * {@code gn_sinu} kernel to precisely these two lines.
 */
public class SinusoidalProjection extends PseudoCylindricalProjection {

	private static final long serialVersionUID = -5905017313852248015L;

	/** Upstream's {@code EPS10}, used only by the ellipsoidal inverse's pole guard. */
	private static final double EPS10 = 1e-10;

	/** Upstream's {@code Q-&gt;en}; null on a sphere, exactly as upstream leaves it. */
	private MeridianArc meridian;

	/** Builds the meridian-arc series when the figure is an ellipsoid. */
	@Override
	public void initialize() {
		super.initialize();
		meridian = es != 0.0 ? MeridianArc.fromEs(es) : null;
	}

	/** Port of {@code gn_sinu_s_forward} / {@code gn_sinu_e_forward}. */
	public ProjCoordinate project(double lam, double phi, ProjCoordinate xy) {
		if (meridian == null) {
			xy.x = lam * FastStrictTrig.cos(phi);
			xy.y = phi;
			return xy;
		}
		final double s = FastStrictTrig.sin(phi);
		final double c = FastStrictTrig.cos(phi);
		xy.y = meridian.mlfn(phi, s, c);
		xy.x = lam * c / Math.sqrt(1. - es * s * s);
		return xy;
	}

	/**
	 * Port of {@code gn_sinu_s_inverse} / {@code gn_sinu_e_inverse}.
	 *
	 * @throws ProjectionException {@link ErrorCause#COORDINATE_OUT_OF_DOMAIN} when the recovered
	 *         latitude is more than {@link #EPS10} past a pole — upstream's third branch, which
	 *         sets {@code PROJ_ERR_COORD_TRANSFM_OUTSIDE_PROJECTION_DOMAIN}. At the pole itself
	 *         (within {@code EPS10}) the longitude is 0, not a division by {@code cos(pi/2)}.
	 */
	public ProjCoordinate projectInverse(double x, double y, ProjCoordinate lp) {
		if (meridian == null) {
			lp.x = x / FastStrictTrig.cos(y);
			lp.y = y;
			return lp;
		}
		final double phi = meridian.invMlfn(y);
		lp.y = phi;
		final double s = Math.abs(phi);
		if (s < ProjectionMath.HALFPI) {
			final double sp = FastStrictTrig.sin(phi);
			lp.x = x * Math.sqrt(1. - es * sp * sp) / FastStrictTrig.cos(phi);
		} else if (s - EPS10 < ProjectionMath.HALFPI) {
			lp.x = 0.;
		} else {
			throw new ProjectionException(ErrorCause.COORDINATE_OUT_OF_DOMAIN, this,
					"Sinusoidal inverse: northing " + y + " inverts to latitude "
							+ Math.toDegrees(phi) + " degrees, which is past a pole");
		}
		return lp;
	}

	public double getWidth(double y) {
		return ProjectionMath.normalizeLongitude(Math.PI) * Math.cos(y);
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isEqualArea() {
     return true;
	}

	public String toString() {
		return "Sinusoidal";
	}

}

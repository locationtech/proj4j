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

import org.locationtech.proj4j.InvalidValueException;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.util.FastStrictTrig;
import org.locationtech.proj4j.util.ProjectionMath;

public class LoximuthalProjection extends PseudoCylindricalProjection {

	private static final long serialVersionUID = 8367744377007360135L;

	private final static double FC = .92131773192356127802;
	private final static double RP = .31830988618379067154;
	private final static double EPS = 1e-8;
	
	private double phi1;
	private double cosphi1;
	private double tanphi1;

	public LoximuthalProjection() {
		initialize();
	}

	/**
	 * Derives the reference parallel from {@code +lat_1}. Port of {@code PJ_PROJECTION(loxim)}
	 * ({@code 9.8.1:src/projections/loxim.cpp:57-77}).
	 * <p>
	 * <b>{@code +lat_1} used to be hard-coded to 40&deg; behind a {@code //FIXME - param}</b>, so
	 * every {@code loxim} definition silently got the wrong reference parallel — PROJ's default is
	 * 0, and the 8 {@code builtins.gie} assertions all pass {@code +lat_1=0.5}. The parameter is
	 * dispatched by {@code Proj4Parser} already; nothing but this class had to change.
	 *
	 * @throws InvalidValueException if {@code cos(lat_1) < 1e-8}, i.e. {@code |lat_1|} is at or
	 *                               past 90 degrees, which upstream rejects with
	 *                               {@code PROJ_ERR_INVALID_OP_ILLEGAL_ARG_VALUE}
	 */
	@Override
	public void initialize() {
		super.initialize();
		phi1 = projectionLatitude1;
		cosphi1 = FastStrictTrig.cos(phi1);
		if (cosphi1 < EPS) {
			throw new InvalidValueException(
					"Invalid value for +lat_1: |lat_1| should be < 90 degrees, but is "
							+ Math.toDegrees(phi1));
		}
		tanphi1 = FastStrictTrig.tan(ProjectionMath.QUARTERPI + 0.5 * phi1);
	}

	/**
	 * Port of {@code loxim_s_forward} ({@code 9.8.1:src/projections/loxim.cpp:21-36}).
	 * <p>
	 * The near-parallel guard is {@code fabs(xy.y) < EPS} upstream; it was {@code y < EPS} here,
	 * so the whole southern half of the map — every point with {@code phi < lat_1} — took the
	 * {@code lam * cos(lat_1)} branch that only applies <em>at</em> the reference parallel.
	 */
	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		double x;
		double y = lpphi - phi1;
		if (Math.abs(y) < EPS)
			x = lplam * cosphi1;
		else {
			x = ProjectionMath.QUARTERPI + 0.5 * lpphi;
			if (Math.abs(x) < EPS || Math.abs(Math.abs(x) - ProjectionMath.HALFPI) < EPS)
				x = 0.;
			else
				x = lplam * y / Math.log( FastStrictTrig.tan(x) / tanphi1 );
		}
		out.x = x;
		out.y = y;
		return out;
	}

	/**
	 * Port of {@code loxim_s_inverse} ({@code 9.8.1:src/projections/loxim.cpp:38-55}).
	 * <p>
	 * Two operand substitutions are corrected: upstream forms {@code M_FORTPI + 0.5 * lp.phi} from
	 * the <em>recovered latitude</em>, not from the northing, and its second guard tests
	 * {@code fabs(fabs(lp.lam) - M_HALFPI)}, not {@code fabs(easting)}.
	 */
	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		double latitude = xyy + phi1;
		double longitude;
		if (Math.abs(xyy) < EPS)
			longitude = xyx / cosphi1;
		else if (Math.abs( longitude = ProjectionMath.QUARTERPI + 0.5 * latitude ) < EPS ||
			Math.abs(Math.abs(longitude) - ProjectionMath.HALFPI) < EPS)
			longitude = 0.;
		else
			longitude = xyx * Math.log( FastStrictTrig.tan(longitude) / tanphi1 ) / xyy;

		out.x = longitude;
		out.y = latitude;
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public String toString() {
		return "Loximuthal";
	}

}

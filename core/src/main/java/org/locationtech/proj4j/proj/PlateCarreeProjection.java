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

import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.ProjectionException;
import org.locationtech.proj4j.util.ProjectionMath;

public class PlateCarreeProjection extends CylindricalProjection {

	/** Spherical: cos(lat_ts); ellipsoidal: nu1 * cos(lat_ts) */
	private double rc;
	/** Meridional arc at lat_0 (ellipsoidal only) */
	private double M0;
	private double[] en;

	public void initialize() {
		super.initialize();
		double cosphits = Math.cos(trueScaleLatitude);
		if (cosphits <= 0.)
			throw new ProjectionException("Invalid value for lat_ts: |lat_ts| should be <= 90");
		if (!spherical) {
			// Ellipsoidal case (EPSG:1028)
			double sinphits = Math.sin(trueScaleLatitude);
			double nu1 = 1.0 / Math.sqrt(1.0 - es * sinphits * sinphits);
			rc = nu1 * cosphits;
			en = ProjectionMath.enfn(es);
			M0 = ProjectionMath.mlfn(projectionLatitude, Math.sin(projectionLatitude),
					Math.cos(projectionLatitude), en);
		} else {
			// Spherical case (EPSG:1029)
			rc = cosphits;
			en = null;
			M0 = 0.0;
		}
	}

	public ProjCoordinate project(double lplam, double lpphi, ProjCoordinate out) {
		out.x = rc * lplam;
		if (spherical)
			out.y = lpphi - projectionLatitude;
		else
			out.y = ProjectionMath.mlfn(lpphi, Math.sin(lpphi), Math.cos(lpphi), en) - M0;
		return out;
	}

	public ProjCoordinate projectInverse(double xyx, double xyy, ProjCoordinate out) {
		out.x = xyx / rc;
		if (spherical)
			out.y = xyy + projectionLatitude;
		else
			out.y = ProjectionMath.inv_mlfn(xyy + M0, es, en);
		return out;
	}

	public boolean hasInverse() {
		return true;
	}

	public boolean isRectilinear() {
		return true;
	}

	public String toString() {
		return "Plate Carr\u00e9e";
	}

}
